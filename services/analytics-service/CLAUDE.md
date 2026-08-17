# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`analytics-service` is one service in the **FinSight** monorepo (`D:\finsight\services\`).
It owns **spending analytics**: a per-user, per-month, per-category **rollup read model**
built from the transaction event stream. It is self-contained — it does **not** call any
other service at runtime, and it must not touch their code. Its only inbound data paths
are HTTP (the read API) and one Kafka listener.

Stack: Java 21 + Spring Boot 4.0.6 + Spring Data JPA + Flyway + MySQL + Spring Kafka.
Listens on port **8088** (auth=8081, user=8082, transaction=8083, budget=8084,
dashboard=8085, risk=8086, notification=8087 by convention).

It is modelled directly on `notification-service` and shares its conventions verbatim:
the response envelope, the JWT stack (same RS256 verification), the exception handler, the
Kafka consumer + idempotency-inbox pattern, the optional OpenAI-compatible LLM seam, and
the Testcontainers test style.

## Scope

Consumes `TransactionCreated` (on `finsight.transactions.created`, owned by
transaction-service) and folds each event into the `monthly_category_rollup` table — the
third consumer of that topic, after budget-service and risk-service. There are no
cross-service runtime calls; category **names** are resolved from a static `CategoryCatalog`
that mirrors transaction-service's seed (the event carries only a `categoryId`).

Read API (`/api/v1/analytics`, all JWT-validated and user-scoped):
- `GET /overview` — month-over-month income/expense/net, savings rate, top movers.
- `GET /categories` — per-category breakdown over a `from`/`to` month range.
- `GET /forecast` — run-rate projection of month-end spend.
- `GET /summary` — a monthly narrative (template by default, optional LLM).

**Produces `MonthlyReportReady`** (on `finsight.reports.monthly`, consumed by
notification-service) — the service's only outbound event. `MonthlyReportScheduler` sweeps daily
and publishes the *previous* month once per user, because "the month ended" is not something any
service publishes. Dedup is producer-side (`monthly_report_sent`), since there is no upstream
event id to key on, and the sent-row is written **before** the publish: a crash between them
costs one report, the other order would re-send to everyone daily. The event carries the finished
figures — notification-service cannot read `analytics_db`. **Single instance**, and gated on
`finsight.kafka.enabled` like the consumer **plus `finsight.report.monthly.enabled`, which is off
by default** — the first sweep against a populated database mails everyone who was active last
month, so it is turned on deliberately, not by the deploy that ships it.

Distinct from `dashboard-service`: that BFF aggregates live over HTTP and owns no data;
this service owns `analytics_db` and answers from a pre-aggregated model.

**Forecast model** (`forecast/`): `daily_category_rollup` carries the same fold at day
granularity — written in the same transaction as the monthly row, because a month total cannot be
taken apart into days afterwards and the weekly pattern lives entirely in that detail.
`SpendingModelTrainer` fits one `spending_model` per (user, currency): Holt level + trend over a
120-day window with a multiplicative weekly season, smoothing constants chosen by grid search on
in-sample SSE. A user's weekday indices are blended toward the population's shape with a weight
that decays as their own evidence grows, so a three-week-old account is not handed seven indices
estimated from three observations each. `ModelTrainingScheduler` retrains nightly at 02:40, always
up to **yesterday** (a partial day looks like a collapse in spending), **single instance**, gated
on `finsight.forecast.model.enabled` — **off by default**.

**Fitting and serving are separate decisions.** Every fit is scored by `HoldoutBacktest`: the last
14 days (two whole weeks, so no weekday is over-weighted) are withheld, the model is refitted on
what remains, and both it and the run rate predict those days blind. The two MAEs land in
`spending_model.{model_mae, baseline_mae, holdout_days}` (**nullable — null means "not measured",
which is treated as a loss**). The baseline averages only the trailing 28 days, because the run
rate never looks further back than a month and beating a strawman is not evidence; the population
prior used inside the backtest is refitted without the holdout too, or the score would be reading
its own answer. `/forecast` serves a model only while days remain in the month, only when one has
been fitted, **and only when `SpendingModel.beatsRunRate()`** — the model must beat the run rate by
at least `BacktestResult.REQUIRED_IMPROVEMENT` (5%), so a tie goes to the simpler projection.
Otherwise the run-rate projection answers unchanged. The response's `method` field says which did.

`ForecastModelMetrics` publishes the population-level answer — `finsight.analytics.forecast.models`
tagged `serving`/`beaten`/`unvalidated`, plus `…model.error.ratio` (mean `model_mae/baseline_mae`).
It is **refreshed at startup and after each sweep, never per scrape**: the values change once a
night, and a gauge supplier would otherwise run COUNT queries on every Prometheus scrape. Counting
winners uses `SpendingModelRepository.countBeatingRunRate(factor)` with the factor passed in, so
the gauge and the forecast can never drift onto different definitions of "wins".

Deliberately deferred: category-level models (the fit is per user + currency),
auto-categorization, and a persisted AI-summary cache (summaries are computed on demand).

## Commands

Use the Maven wrapper. On Windows use `mvnw.cmd`; on the Bash tool use `./mvnw`.

```bash
mvnw.cmd test                                   # all tests (Docker needed for Testcontainers)
mvnw.cmd test -Dtest=TemplateSummarizerTest     # single test class
mvnw.cmd -o -q test-compile                      # offline compile check
mvnw.cmd package                                # build jar
```

Running locally requires (DB defaults exist; `JWT_PUBLIC_KEY` does not):

```
JWT_PUBLIC_KEY=<auth-service's public key>   # required, no default; verification only
JWT_JWKS_URI=http://localhost:8081/.well-known/jwks.json   # optional; enables key rotation
DB_URL=jdbc:mysql://localhost:3306/analytics_db
DB_USERNAME=root
DB_PASSWORD=
```

## Architecture and conventions

Layering is strict and one-directional: `controller → service → repository`.

- **Controllers are thin.** They resolve `userId` from the JWT principal, delegate, and
  wrap results in the response envelope. See `AnalyticsController`.
- **`userId` is sacred.** Read ONLY from the JWT (`userId` claim) via `JwtUserPrincipal`,
  never from the request. Every service method and every repository query is `userId`-scoped.

### Read model & Kafka consumer
- `TransactionCreatedConsumer` (gated by `finsight.kafka.enabled`; off in the test
  profile) consumes `TransactionCreated` and delegates to `RollupService.apply(...)`.
- **Idempotency**: a `processed_events` inbox row is written in the SAME transaction as
  the rollup upsert; redelivered eventIds are skipped (no double-counting). Never bypass it.
- One rollup row per `(user_id, year_month, category_id, type, currency)` — a unique slot.
  `categoryId == 0` is the uncategorized sentinel (the event may omit categoryId).
- The listener runs single-threaded (default concurrency 1), so the read-modify-write on a
  rollup row is uncontended; do not raise concurrency without an upsert/locking strategy.
- The consumer-side `TransactionCreatedEvent` record is a deliberate copy of
  transaction-service's wire contract (`type` as String). Do not import or share code.
- Outcome counters: `finsight.analytics.{applied,duplicate,ignored,failed}`.

### Persistence
- Schema is owned by **Flyway**; JPA is `ddl-auto=validate`. New schema => new `V{n}__*.sql`,
  never edit an applied migration. The one index leads with `(user_id, year_month)`.
- The month column is `period_month` (`CHAR(7)` `'YYYY-MM'`) — it sorts and `BETWEEN`s
  chronologically. It is **not** named `year_month`: `YEAR_MONTH` is a reserved word in MySQL.
  The JPA property stays `yearMonth`, so derived queries (`...AndYearMonth`) are unchanged.

### Summarization (optional LLM)
- `Summarizer` turns a month's anonymized aggregate (`MonthlySummaryData`) into text.
  `TemplateSummarizer` is the default, deterministic implementation (used by tests — no
  network).
- `LlmSummarizer` (gated by `finsight.summarizer.ai.enabled`, `@Primary` when on) calls an
  OpenAI-compatible Chat Completions API (default Groq, free tier) and parses a JSON
  `{summary}`. On ANY failure (timeout, non-2xx, bad JSON, empty field) it returns
  `TemplateSummarizer.summarize(...)`. Config: `finsight.summarizer.ai.{enabled,base-url,
  api-key,model,timeout-ms,max-tokens}` (env `FINSIGHT_SUMMARIZER_AI_ENABLED`, `LLM_API_KEY`,
  `LLM_BASE_URL`, `LLM_MODEL` — shared platform-wide). Counters:
  `finsight.analytics.ai.{success,fallback}`.
- **Privacy**: unlike notification-service's narrator (which sends zero figures), the
  summary sends AGGREGATED amounts + category names so the model can describe them. It
  never sends a userId, email, or any individual transaction — no identity leaves the service.
- **The summary endpoint is NOT `@Transactional`**: the reads run in short auto-commits,
  then `summarize()` (which may call the LLM) runs after they close, so no DB connection is
  held open across the network hop. The other read endpoints are `@Transactional(readOnly)`.

### API contract
- Success envelope: `{ "success": true, "data": ... }` (no pagination, no `meta`). Error
  envelope: `{ "success": false, "error": { "code", "message" } }` via
  `GlobalExceptionHandler` (codes `INVALID_PARAMETER`, `VALIDATION_ERROR`, ...).
- `year`/`month` default to the current month; `currency` is optional (the user's dominant
  currency for the period is used when absent).
