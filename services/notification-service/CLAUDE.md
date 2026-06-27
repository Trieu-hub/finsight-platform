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
response envelope, the JWT stack (same shared secret), the exception handler, the Kafka
consumer + idempotency-inbox pattern, and the Testcontainers test style.

## Scope

Consumes `RiskDetected` (on `finsight.risk.detected`, owned by risk-service) and creates
one notification per detection. notification-service is the **first consumer** of that
topic — before it, `RiskDetected` was produced with no consumer. There are no
cross-service runtime calls.

The message wording comes from an `AlertNarrator`. The default `TemplateNarrator` is rule-based
and always on. An optional `LlmAlertNarrator` (off by default, `finsight.narrator.ai.enabled`)
phrases the alert with an LLM over any **OpenAI-compatible** Chat Completions API — default Groq
(free tier), swappable to OpenAI/OpenRouter/Ollama by config alone. It is `@Primary` when enabled
and falls back to `TemplateNarrator` on any error, so the pipeline never depends on the API.

Deliberately deferred: external delivery channels (email/push/webhook — only in-app for now).

## Commands

Use the Maven wrapper. On Windows use `mvnw.cmd`; on the Bash tool use `./mvnw`.

```bash
mvnw.cmd test                                       # all tests (Docker needed for Testcontainers)
mvnw.cmd test -Dtest=TemplateNarratorTest           # single test class
mvnw.cmd -o -q test-compile                          # offline compile check
mvnw.cmd package                                     # build jar
```

Running locally requires (DB defaults exist; `JWT_SECRET` does not):

```
JWT_SECRET=<same secret as auth-service>   # required, no default
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
  `PATCH /api/v1/notifications/{id}/read`, `PATCH /api/v1/notifications/read-all`.
