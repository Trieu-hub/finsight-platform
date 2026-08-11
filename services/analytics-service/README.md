# analytics-service

Spending **analytics** for FinSight, built as a **CQRS read model** over the transaction
event stream. It consumes `TransactionCreated` from Kafka, folds each event into a
per-user, per-month, per-category rollup, and serves month-over-month, category-breakdown,
forecast, and AI-summary queries straight off that rollup — never scanning raw
transactions, never calling another service at request time.

- **Port:** 8088
- **Owns:** `analytics_db` (`monthly_category_rollup`, `processed_events`)
- **Consumes:** `finsight.transactions.created` (owned by transaction-service)
- **Auth:** Bearer JWT, validated locally with auth-service's RS256 public key; every figure is
  scoped to the caller's `userId`.

## How it works

```
transaction-service ──TransactionCreated──▶ Kafka ──▶ analytics-service
                                                          │  consume + idempotency inbox
                                                          ▼
                                                    monthly_category_rollup
                                                          │
                       web / gateway ──JWT──▶ GET /api/v1/analytics/*
                                                          │  (optional LLM for /summary)
                                                          ▼
                                                  Groq / OpenAI-compatible
```

Each `TransactionCreated` upserts one rollup slot
(`user_id, year_month, category_id, type, currency`), adding the amount and incrementing
the count. The idempotency inbox (`processed_events`) makes a redelivered event a no-op.

## Endpoints

All under `/api/v1/analytics`, all requiring a Bearer JWT. `year`/`month` default to the
current month; `currency` is optional (defaults to the user's dominant currency).

| Method & path | Returns |
|---|---|
| `GET /overview?year=&month=&currency=` | This vs last month: income/expense/net, savings rate, % changes, top movers |
| `GET /categories?from=YYYY-MM&to=YYYY-MM&currency=` | Per-category totals + share over a month range |
| `GET /forecast?year=&month=&currency=` | Month-end spend projection. Run-rate by default; the fitted `spending_model` when one exists and days remain in the month — `method` says which, and `projectedLow`/`projectedHigh` carry the model's error band |
| `GET /summary?year=&month=&currency=` | A monthly narrative (template, or LLM when enabled) |

Response envelope: `{ "success": true, "data": ... }`.

## AI monthly summary (optional, off by default)

`GET /summary` returns a short narrative. By default it is produced by a deterministic
**template**. Flip on the optional LLM path (any **OpenAI-compatible** API, default **Groq**
free tier) to have a model phrase it instead:

```
FINSIGHT_SUMMARIZER_AI_ENABLED=true
LLM_API_KEY=gsk_...        # get a free key at https://console.groq.com
```

The response carries `aiGenerated` so the client knows which path answered. On **any** LLM
error the service falls back to the template — the endpoint never fails because of the API.

**Privacy:** only aggregated figures and category names are sent to the model — never a
userId, email, or any individual transaction.

Swap providers without code changes via `LLM_BASE_URL` + `LLM_MODEL` (OpenAI, OpenRouter,
a local Ollama, ...). This is *phrasing* only — the figures themselves are computed locally.

## Run

Part of the platform stack:

```bash
docker compose up -d --build analytics-service
```

Locally with Maven (needs MySQL + Kafka + `JWT_PUBLIC_KEY`):

```bash
mvnw.cmd spring-boot:run
```

## Metrics

Exposed at `/actuator/prometheus`:

- `finsight.analytics.applied` / `duplicate` / `ignored` / `failed` — consumer outcomes.
- `finsight.analytics.ai.success` / `fallback` — LLM summary vs template fallback.
