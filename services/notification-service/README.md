# notification-service

In-app notifications for the **FinSight** platform. Consumes `RiskDetected` events and
materializes one per-user notification per detection. Java 21 · Spring Boot 4.0.6 ·
Spring Data JPA · Flyway · MySQL · Spring Kafka. Port **8087**.

## Scope

Event-driven: subscribes to `finsight.risk.detected` (owned by `risk-service`) and is its
first consumer. Each `RiskDetected` becomes one notification, deduplicated by an
idempotency inbox. No cross-service runtime calls. Only in-app notifications for now —
email/push and an LLM-backed message narrator are deferred (the `AlertNarrator` seam
exists; the default `TemplateNarrator` is rule-based).

## Run

```bash
# requires a running MySQL and: CREATE DATABASE notification_db;
JWT_SECRET=<same secret as auth-service> \
DB_URL=jdbc:mysql://localhost:3306/notification_db DB_USERNAME=root DB_PASSWORD= \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
mvnw.cmd spring-boot:run
```

`JWT_SECRET` is required (no default). Tokens are validated locally with the secret shared
with `auth-service`.

### Optional AI narrator

By default the rule-based `TemplateNarrator` writes the alert text — no key, no network. To have
an LLM phrase the alert instead, set (any OpenAI-compatible API; default is **Groq**, free tier):

```bash
FINSIGHT_NARRATOR_AI_ENABLED=true \
LLM_API_KEY=gsk_...                 # free key from https://console.groq.com
# optional overrides — defaults target Groq llama-3.1-8b-instant:
# LLM_BASE_URL=https://api.openai.com/v1   LLM_MODEL=gpt-4o-mini   (OpenAI)
# LLM_BASE_URL=http://localhost:11434/v1   LLM_MODEL=llama3.1      (local Ollama)
```

Only `riskType`/`riskSeverity` are sent upstream (no PII). Any failure (timeout, non-2xx, bad
JSON) silently falls back to `TemplateNarrator`, so the consumer never breaks.

## Test

```bash
mvnw.cmd test     # unit + integration (integration needs Docker for Testcontainers MySQL 8)
```

## API

All endpoints require a `Bearer <jwt>` token; `userId` is taken from the token only, so a
caller can only ever reach their own notifications.

| Method | Path | Description |
|--------|------|-------------|
| GET   | `/api/v1/notifications`              | List newest-first; `unreadOnly`, `page`, `limit` (1-based) |
| GET   | `/api/v1/notifications/unread-count` | `{ "count": n }` of unread |
| PATCH | `/api/v1/notifications/{id}/read`    | Mark one read (404 if not owned) |
| PATCH | `/api/v1/notifications/read-all`     | Mark all unread read; returns count affected |

Envelopes: success `{ "success": true, "data": ..., "meta": ... }`,
error `{ "success": false, "error": { "code", "message" } }`.

## Event consumed

`RiskDetected` on `finsight.risk.detected`:

```json
{
  "eventId": "…", "eventType": "RiskDetected", "occurredAt": "2026-06-26T10:00:00Z",
  "userId": 42, "transactionId": "…", "riskType": "HIGH_AMOUNT_EXPENSE", "riskSeverity": "HIGH"
}
```

Events with no `eventId` (cannot dedup) or no `userId` (no recipient) are ignored.
Outcome counters: `finsight.notifications.{created,duplicate,ignored,failed}`.

See `CLAUDE.md` for full conventions and the rationale behind what is deferred.
