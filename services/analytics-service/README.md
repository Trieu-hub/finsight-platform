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
| `GET /forecast?year=&month=&currency=` | Month-end spend projection. Run-rate by default; the fitted `spending_model` when days remain in the month **and its holdout backtest beat the run rate** — `method` says which answered, and `projectedLow`/`projectedHigh` carry the model's error band (null under the run rate, which has none) |
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

## Spend forecast model (optional, off by default)

`FINSIGHT_FORECAST_MODEL_ENABLED=true` turns on `ModelTrainingScheduler`, which retrains every
user nightly at 02:40 — always up to **yesterday**, since a partial day looks like a collapse in
spending. Nothing here is a library call, a network call, or random: the same series always
yields the same model.

**What is fitted.** `daily_category_rollup` carries the same event fold at day granularity (a
month total cannot be taken apart into days afterwards). Per `(user, currency)`,
`SeasonalTrendTrainer` fits Holt's level + trend with a multiplicative **weekly season**, its
smoothing constants chosen by grid search, and stores the parameters in `spending_model` — seven
weekday indices as seven columns, so a fit is inspectable with a plain `SELECT`.

**Cold start.** A three-week-old account fitted alone would get seven weekday indices from three
observations each — personalised-looking noise. Each user's indices are blended toward the
population's weekly shape with a weight that decays as their own evidence grows.

**Serving is a separate decision from fitting.** The smoothing constants are chosen on *in-sample*
error, which only measures how well the model memorised its own window, so every fit is scored by
`HoldoutBacktest`: the last 14 days (two whole weeks, so no weekday is over-weighted) are withheld,
the model is refitted on what remains, and both it and the run rate predict those days blind. The
two MAEs land in `spending_model.{model_mae, baseline_mae, holdout_days}`, **nullable — null means
"not measured", which counts as a loss**. `/forecast` serves the model only when it beat the run
rate by ≥5%; a tie goes to the simpler projection. The baseline averages only the trailing 28 days,
because the run rate never looks further back than a month and beating a strawman is not evidence.

Consequences worth knowing before turning this on: the daily series only accumulates **from the
deploy forward** (the raw history lives in `transaction_db`, which this service must not read), at
least 28 days of it are needed before any model can be validated, and a user whose spending is
genuinely flat will never be served one — the run rate already answers them exactly.

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
- `finsight.analytics.forecast.models.trained` / `training.failed` — nightly training sweep
  (only registered when the forecast model is enabled). How many of those fits actually beat the
  run rate on their holdout is logged by the sweep, not counted here.
