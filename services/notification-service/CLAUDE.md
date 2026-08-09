# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`notification-service` is one service in the **FinSight** monorepo (`D:\finsight\services\`).
It owns **in-app notifications**: a per-user message materialized from an upstream event.
It is self-contained — it does **not** call any other service at runtime, and it must not
touch their code. Its only inbound data paths are HTTP (the read API) and one Kafka listener.

Stack: Java 21 + Spring Boot 4.0.6 + Spring Data JPA + Flyway + MySQL + Spring Kafka.
Listens on port **8087** (auth=8081, user=8082, transaction=8083, budget=8084,
dashboard=8085, risk=8086 by convention).

It is modelled directly on `budget-service` and shares its conventions verbatim: the
response envelope, the JWT stack (same RS256 verification), the exception handler, the Kafka
consumer + idempotency-inbox pattern, and the Testcontainers test style.

## Scope

Consumes `RiskDetected` (on `finsight.risk.detected`, owned by risk-service) and creates
one notification per detection. notification-service is the **first consumer** of that
topic — before it, `RiskDetected` was produced with no consumer. There are no
cross-service runtime calls.

Also consumes **`BudgetExceeded`** (on `finsight.budgets.exceeded`, owned by budget-service) —
the over-budget alert, raised once per crossing from the authoritative `spent_amount`. Both feeds
converge on the same private `create(...)` in `NotificationServiceImpl`: same inbox, same
transaction, same after-commit fan-out. It needs its **own listener container factory**
(`budgetExceededListenerContainerFactory`) because the wire format is headerless — one JSON
default type per factory — and the auto-configured one is pinned to `RiskDetectedEvent`. That
factory is outside Boot's reach, so the `CorrelationIdRecordInterceptor` is attached by hand;
forget it and this is the one consumed event whose log lines fall out of the trace.

Also consumes **`MonthlyReportReady`** (on `finsight.reports.monthly`, owned by analytics-service)
— the month in review, published once per user per month by a scheduled sweep there. It needs its
own factory (`monthlyReportListenerContainerFactory`) and consumer group for the same headerless-
wire-format reason as `BudgetExceeded`, and converges on the same private `create(...)`. It is not
an alert: type `MONTHLY_REPORT`, severity `LOW`. The figures ride on the event because this
service cannot reach `analytics_db` and must not call analytics-service at runtime.

The message wording comes from an `AlertNarrator`. The default `TemplateNarrator` is rule-based
and always on. An optional `LlmAlertNarrator` (off by default, `finsight.narrator.ai.enabled`)
phrases the alert with an LLM over any **OpenAI-compatible** Chat Completions API — default Groq
(free tier), swappable to OpenAI/OpenRouter/Ollama by config alone. It is `@Primary` when enabled
and falls back to `TemplateNarrator` on any error, so the pipeline never depends on the API.

Alerts also leave the app through **delivery channels** (`delivery/DeliveryChannel`): **web push**
to subscribed browsers, **email** over SMTP, and an outbound **signed webhook** to a URL the user
nominated. All are configuration-gated and inert by default, so a fresh checkout still behaves
exactly as before — in-app bell + SSE only.

Each user also chooses a **digest mode** (`IMMEDIATE` | `HOURLY` | `DAILY`) that batches the
content-carrying channels; `delivery/DigestScheduler` sends what the create path deliberately held
back.

Deliberately deferred: retry/backoff for a failed webhook (one attempt, best-effort like every
other channel), and per-alert-type or per-severity channel routing.

## Commands

Use the Maven wrapper. On Windows use `mvnw.cmd`; on the Bash tool use `./mvnw`.

```bash
mvnw.cmd test                                       # all tests (Docker needed for Testcontainers)
mvnw.cmd test -Dtest=TemplateNarratorTest           # single test class
mvnw.cmd -o -q test-compile                          # offline compile check
mvnw.cmd package                                     # build jar
```

Running locally requires (DB defaults exist; `JWT_PUBLIC_KEY` does not):

```
JWT_PUBLIC_KEY=<auth-service's public key>   # required, no default; verification only
JWT_JWKS_URI=http://localhost:8081/.well-known/jwks.json   # optional; enables key rotation
DB_URL=jdbc:mysql://localhost:3306/notification_db
DB_USERNAME=root
DB_PASSWORD=
```

## Architecture and conventions

Layering is strict and one-directional: `controller → service → repository`.

- **Controllers are thin.** They resolve `userId` from the JWT principal, delegate, and
  wrap results in the response envelope. See `NotificationController`.
- **`userId` is sacred.** Read ONLY from the JWT (`userId` claim) via `JwtUserPrincipal`,
  never from the request. Every service method and every repository query is `userId`-scoped.

### Persistence
- `Notification` PK is a `UUID` generated in app code.
- Schema is owned by **Flyway**; JPA is `ddl-auto=validate`. New schema => new `V{n}__*.sql`,
  never edit an applied migration. Two indexes on `notifications` both lead with `user_id`.
- Read state (`is_read`/`read_at`) is the only mutable part; everything else is set once.

### Kafka consumer
- `RiskDetectedConsumer` (gated by `finsight.kafka.enabled`; off in the test profile)
  consumes `RiskDetected` and delegates to `NotificationService.createFromEvent(...)`.
- **Idempotency**: a `processed_events` inbox row is written in the SAME transaction as the
  notification insert; redelivered eventIds are skipped. Never bypass it.
- Events with no `eventId` (cannot dedup) or no `userId` (no recipient) are ignored and
  deliberately NOT recorded in the inbox.
- The consumer-side `RiskDetectedEvent` record is a deliberate copy of risk-service's wire
  contract (`riskType`/`riskSeverity` as String). Do not import or share code.
- Outcome counters: `finsight.notifications.{created,duplicate,ignored,failed}`.

### Narration
- `AlertNarrator` turns a `RiskDetectedEvent` into title + message. `TemplateNarrator` is the
  default, rule-based, deterministic implementation (used by tests — no network).
- `LlmAlertNarrator` (gated by `finsight.narrator.ai.enabled`, `@Primary` when on) calls an
  OpenAI-compatible Chat Completions API (default Groq, free tier) and parses a JSON
  `{title, message}`. It sends only `riskType`/`riskSeverity` — **no PII** — caps the call with a
  short timeout, and on ANY failure (timeout, non-2xx, bad JSON, empty fields) returns
  `TemplateNarrator.narrate(...)`. Config: `finsight.narrator.ai.{enabled,base-url,api-key,model,
  timeout-ms,max-tokens}` (env `FINSIGHT_NARRATOR_AI_ENABLED`, `LLM_API_KEY`, `LLM_BASE_URL`,
  `LLM_MODEL`). Outcome counters: `finsight.notifications.ai.{success,fallback}`.
- **Narration runs OUTSIDE the DB transaction** (`NotificationServiceImpl`): the inbox dedup
  check short-circuits first (no LLM call for duplicates), then narrate, then only the two
  inserts run in a `TransactionTemplate` — so an external call never holds a DB connection open.

### API contract
- Pagination is 1-based in the API, 0-based in Spring Data — the controller subtracts 1.
- Success envelope: `{ "success": true, "data": ..., "meta": ... }`. Error envelope:
  `{ "success": false, "error": { "code", "message" } }` via `GlobalExceptionHandler`
  (stable code `NOTIFICATION_NOT_FOUND`).
- Endpoints (all require a Bearer JWT, all user-scoped):
  `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`,
  `PATCH /api/v1/notifications/{id}/read`, `PATCH /api/v1/notifications/read-all`,
  `GET /api/v1/notifications/stream` (SSE),
  `GET|PUT /api/v1/notifications/preferences`,
  `PUT /api/v1/notifications/preferences/webhook`, `PUT /api/v1/notifications/preferences/digest`,
  `GET /api/v1/push/public-key`, `POST|DELETE /api/v1/push/subscriptions`.
  The gateway routes `/api/v1/push` here as well as `/api/v1/notifications`.
  `SecurityConfig` ends in `anyRequest().authenticated()`, so a new endpoint is covered without
  touching it — check that still holds before assuming it for anything public.

### Delivery channels
- `DeliveryChannel` implementations run in `NotificationServiceImpl.createFromEvent` **after the
  commit**, next to the SSE publish, and each one **swallows its own failures**. A throw would fail
  the Kafka listener, the event would be redelivered, and every user who already got their alert
  would get it again.
- The interface takes a **batch** (`deliver(userId, List<Notification>)`), not one notification.
  The immediate path is a batch of one; a digest is a batch of many. That keeps the
  immediate-vs-deferred decision with the caller instead of teaching every channel about
  scheduling. `respectsDigest()` marks the channels a digest holds back (email, webhook); web push
  leaves it false.
- **Web push** (`push/`): pushes carry **no payload** — the service worker fetches the notification
  over the normal API instead. That keeps the alert text out of Google's and Mozilla's push
  infrastructure and, because RFC 8291 content encryption is then unnecessary, keeps the crypto
  down to an ES256 VAPID JWT that the jjwt already on the classpath can sign (no web-push library).
  Off until `finsight.push.{public-key,private-key}` are set (`scripts/gen-vapid-keys.sh`).
  A 404/410 from a push service means that browser is gone: the subscription row is deleted.
  The private key is validated to be exactly 32 bytes — a truncated one still *signs*, and the only
  symptom would be the push service answering 401.
- **Email** (`email/`): there is no enabled flag. Spring only creates a `JavaMailSender` when
  `spring.mail.host` is set, so the presence of SMTP configuration is the switch. The body is the
  narrator's existing text, so wording never diverges between channels.
- **The address comes from the JWT.** This service is Kafka-driven and `RiskDetected` carries only
  a userId; it must not call auth-service for a mailbox. So `notification_preferences` stores the
  `email` claim of the caller's own token, captured when they switch email alerts on. That keeps
  the copy an explicit consequence of opting in, and `EmailPreferenceRequest` deliberately has no
  address field — accepting one would let a caller redirect another account's alerts.
- **Webhook** (`webhook/`): POSTs the alert text — unlike push, the destination is a system the
  user controls, so withholding the content would leave them a ping they cannot act on. The payload
  is **always** `{deliveredAt, count, alerts:[…]}`, an array even for one alert, so switching a user
  to a digest does not silently break their integration.
  - `WebhookUrlValidator` is the **SSRF boundary**, not a niceness check: https only, and every
    resolved address must be publicly routable (loopback / RFC 1918 / link-local / CGNAT / IPv6
    ULA all refused). Validated on save *and* before each delivery, because DNS can be repointed;
    redirects are disabled in the client because a 302 smuggles in an unvetted address. Read the
    class comment before relaxing any of it.
  - `WebhookSigner` signs `"<t>.<body>"` — the timestamp is *inside* the MAC so a receiver can
    reject stale replays and know `t` was not rewritten. Serialise once and sign those exact bytes;
    a second serialisation can drift and every signature then reads as invalid.
  - The secret is returned on **exactly one** response, the one that minted it. A changed URL mints
    a new one; toggling the same URL keeps it.
  - Uses the injected **Jackson 3** mapper (`tools.jackson.databind.ObjectMapper`). Boot 4
    autoconfigures no `com.fasterxml` (Jackson 2) mapper bean even though that jar is on the
    classpath — asking for one fails context startup.

### Digests
- `DigestMode` on `notification_preferences`: `IMMEDIATE` (default), `HOURLY`, `DAILY`.
- `notifications.digested_at` null means **still owed an outbound delivery**. Rows for an immediate
  user are stamped as they are created, so null never means "old" — `V4` backfills the history for
  the same reason.
- `DigestScheduler` polls (`finsight.digest.poll-ms`, 5 min) and flushes a user when their
  **oldest** pending alert is older than the window. Poll interval is resolution, not window.
- It **stamps before delivering**: channels are best-effort, and stamping afterwards would turn one
  broken receiver into the same digest resent every five minutes forever.
- Rows are stamped **by id**, never by "everything pending for this user" — an alert arriving
  mid-flush would otherwise be marked delivered without being sent.
- Changing digest mode writes off whatever is pending: a mode change starts a fresh window rather
  than stranding a partial one or resending what was already delivered.
- **Single instance**, like the SSE registry. Two schedulers would each claim the same rows.

### Live push (SSE)
- `NotificationStream` holds the open `SseEmitter`s in a per-user, **in-process** registry.
  `NotificationServiceImpl.createFromEvent` pushes each new notification to it *after* the
  commit, so a rolled-back insert never reaches a bell. Delivery is best-effort — the row is
  already durable in MySQL, and the client keeps a slow poll as a fallback.
- A `@Scheduled` comment heartbeat (25 s) keeps idle streams from being culled by proxies;
  `@EnableScheduling` on the application class exists for this.
- **The registry is per-process.** Running more than one instance would need the push fanned
  out over a shared bus (a user's stream may live on a different instance than the consumer
  that produced their notification). Not built — this deployment runs one instance.
- Two things had to change elsewhere for this to work end-to-end: **api-gateway** buffers whole
  responses, so it now relays `Accept: text/event-stream` on a separate unbuffered path with no
  read timeout; and the **web client** consumes the stream with `fetch` (not `EventSource`,
  which cannot send an `Authorization` header).
