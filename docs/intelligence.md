# FinSight — Intelligence Overview

_Last updated: 2026-06-14 · Source of truth: `services/risk-service`._

All three intelligence domains live in **risk-service**, derived from a single read-model
(`observed_expenses`) fed by the `TransactionCreated` consumer — no ML, no prediction, no
statistical models beyond simple counts/sums/averages, no scheduler. Each consumed transaction
is recorded, then the risk rules, behavioral insights, and the anomaly rule are evaluated in
that order (`RiskEventConsumer.onTransactionCreated`).

Numeric thresholds below are the constants in code at the time of writing.

> **Read-model caveat — `observed_expenses` is append-only and can drift.** The read-model is
> populated solely from `TransactionCreated`; there are no `TransactionUpdated` or
> `TransactionDeleted` events and no backfill/reconciliation job. A transaction edited or
> deleted in transaction-service after the fact is **not** reflected here, so every derived
> figure — risk windows, the spending/category/savings baselines, and the anomaly average —
> can diverge from transaction-service's authoritative ledger over time. This is the same
> accepted, eventually-consistent tradeoff documented for budget-service's `spent_amount`
> (`docs/ADR-0004`): transaction-service remains the source of truth for actual spend; the
> intelligence figures are advisory signals computed from the create-time stream. Fixing it
> would require update/delete events (or periodic reconciliation) and is deliberately out of
> the current MVP scope.

---

## Risk Monitoring

Evaluated by `RiskRuleEngine` on each consumed **EXPENSE**. The expense is first recorded into
`observed_expenses` idempotently (keyed by the source event id), so the windowed rules see it
and a redelivered event is neither double-counted nor re-alerted.

**Shared behavior for all three rules:**
- **Generated artifact:** a `RiskDetected` event on `finsight.risk.detected` (keyed by `userId`,
  consumed by notification-service) **and** a durable `risk_alerts` row.
- **Persistence:** `risk_alerts` (id = the `RiskDetected` event id). Effectively idempotent —
  a redelivered `TransactionCreated` is skipped by the engine's `observed_expenses` dedup, so
  no duplicate alert is produced.
- **Read API:** `GET /api/v1/risks`, `GET /api/v1/risks/{id}`.
- **Metrics:** `finsight.risk.events.detected{type,severity}` per detection;
  `finsight.risk.events.processed` counts every consumed event.

| Rule | Trigger condition | Severity |
|---|---|---|
| **HIGH_AMOUNT_EXPENSE** | This EXPENSE's `amount` ≥ **10,000,000**. | HIGH |
| **RAPID_SPENDING** | This event is the **5th** EXPENSE for the user within a **10-minute** window (`count == 5`) — fires once per burst, not on every later event. | MEDIUM |
| **LARGE_DAILY_SPEND** | This event pushes the user's EXPENSE total for the calendar day from ≤ **20,000,000** to > 20,000,000 (a single crossing per day). | HIGH |

---

## Behavioral Insights

Evaluated by `InsightService` on each consumed transaction. EXPENSE drives all four rules;
INCOME is recorded into `observed_expenses` (the income side feeding LOW_SAVINGS_RATE) but
produces no insight directly.

**Shared behavior for all four insights:**
- **Severity:** not applicable (insights are not severity-graded).
- **Generated artifact:** an `insights` row. **No Kafka event is published.**
- **Persistence:** `insights`, deduplicated by `(userId, insightType, period_month, subject_id)`
  (unique constraint) — "fire once" per scope per month. `subject_id` is `-` for user-level
  insights, the category id for CATEGORY_SURGE, the budget id for BUDGET_RISK.
  `previous_amount` / `current_amount` / `increase_pct` are snapshotted at generation time.
- **Read API:** `GET /api/v1/insights`.
- **Metric:** `finsight.insights.generated{type}` (all types registered eagerly, exported at 0).

| Insight | Trigger condition | Scope (`subject_id`) |
|---|---|---|
| **SPENDING_INCREASE** | Current-month EXPENSE total ≥ **1.30×** the previous month's (≥ +30%); requires a positive previous-month baseline. | user (`-`), one per month |
| **CATEGORY_SURGE** | Current-month total in the event's category ≥ **1.50×** the previous month's in that category (≥ +50%); requires a positive baseline. | category id, one per month |
| **BUDGET_RISK** | For a budget matching the event (user + category + exact currency, txn date within `[start,end]`) with limit > 0, utilization `spent/limit×100` **> 80%** while the period is still open. | budget id |
| **LOW_SAVINGS_RATE** | Current-month income **> 0** and current-month expenses **≥ 80%** of that income. | user (`-`), one per month |

`BUDGET_RISK` reads the `budget_snapshots` read-model maintained from `BudgetChanged`;
`current_amount`/`previous_amount` carry the spent amount and the limit, and `increase_pct`
carries the utilization percentage (e.g. `85.00`). `LOW_SAVINGS_RATE` stores income as
`previous_amount`, expenses as `current_amount`, and the share of income spent as `increase_pct`.

---

## Anomaly Detection

Evaluated by `AnomalyService` on each consumed **EXPENSE**, after the rule engine has recorded it.

| Anomaly | Trigger condition |
|---|---|
| **UNUSUAL_TRANSACTION_AMOUNT** | This EXPENSE's `amount` ≥ **3×** the user's average historical expense amount, once the user has at least **10** prior EXPENSE transactions (those recorded strictly before this event's time; the triggering expense is excluded from its own baseline). |

- **Severity:** not applicable.
- **Generated artifact:** an `anomalies` row. **No Kafka event is published.**
- **Persistence:** `anomalies` (id = the source event id ⇒ idempotent; a redelivered event
  neither double-counts the metric nor inserts a duplicate). `amount`, `average_amount`, and
  `ratio` (`amount / average`) are snapshotted at detection time.
- **Read API:** `GET /api/v1/anomalies`.
- **Metric:** `finsight.anomalies.detected{type="UNUSUAL_TRANSACTION_AMOUNT"}` (registered
  eagerly, exported at 0).

---

## Notification narration (AI, optional)

`notification-service` consumes `RiskDetected` and turns it into a user-facing in-app
notification. The wording is produced by an `AlertNarrator`:

- **`TemplateNarrator`** (default, always on): deterministic rule-based text keyed by `riskType`.
  No network, used by tests.
- **`LlmAlertNarrator`** (optional, `finsight.narrator.ai.enabled=true`): phrases the alert with
  an LLM over any **OpenAI-compatible** Chat Completions API — default **Groq** (free tier,
  `llama-3.1-8b-instant`), swappable to OpenAI/OpenRouter/Ollama by config. It sends only
  `riskType`/`riskSeverity` (**no PII**), is capped by a short timeout, and on ANY failure falls
  back to `TemplateNarrator` — the pipeline never depends on the external API. The LLM call runs
  **outside** the DB transaction and is skipped for duplicate events.

---

## Metrics summary

| Metric | Tags | Meaning |
|---|---|---|
| `finsight.risk.events.processed` | – | every `TransactionCreated` evaluated |
| `finsight.risk.events.detected` | `type`, `severity` | each risk detection |
| `finsight.insights.generated` | `type` | each insight generated |
| `finsight.anomalies.detected` | `type` | each anomaly detected |
| `finsight.notifications.ai.success` | – | alerts narrated by the LLM |
| `finsight.notifications.ai.fallback` | – | alerts that fell back to templates after an LLM error |

The **FinSight Risk** Grafana dashboard visualizes `finsight.risk.events.detected` by type and
severity. (There is no dedicated insights/anomaly dashboard — out of scope for this phase.)
