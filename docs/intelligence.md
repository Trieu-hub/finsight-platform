# FinSight — Intelligence Overview

_Last updated: 2026-08-09 · Source of truth: `services/risk-service`._

All four intelligence domains live in **risk-service**, derived from a single read-model
(`observed_expenses`) fed by the `TransactionCreated` consumer — no ML, no prediction, no
statistical models beyond simple counts/sums/averages. Each consumed transaction is recorded,
then the risk rules, behavioral insights, and the anomaly rule are evaluated in that order
(`RiskEventConsumer.onTransactionCreated`).

Recurring-charge detection (Phase G.1) is the one part that also keeps state of its own
(`recurring_series`) and the one that needs a **scheduler**: two of its three signals come off
the event path like everything else, but "the charge that should have repeated never arrived"
is an absence, and no event is ever published for something that did not happen.

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

Evaluated by `RiskRuleEngine` on each consumed **EXPENSE** and **INCOME** — an expense tracker
should be as suspicious of unexplained money arriving as of money leaving. TRANSFER moves money
between a user's own wallets and is neither, so it is skipped entirely and never recorded. The
event is recorded into `observed_expenses` idempotently (keyed by the source event id) before the
rules run, so the windowed rules see it and a redelivered event is neither double-counted nor
re-alerted.

**Shared behavior for all ten rules:**
- **Generated artifact:** a `RiskDetected` event on `finsight.risk.detected` (keyed by `userId`,
  consumed by notification-service) **and** a durable `risk_alerts` row.
- **Persistence:** `risk_alerts` (id = the `RiskDetected` event id). Effectively idempotent —
  a redelivered `TransactionCreated` is skipped by the engine's `observed_expenses` dedup, so
  no duplicate alert is produced.
- **Read API:** `GET /api/v1/risks`, `GET /api/v1/risks/{id}`.
- **Metrics:** `finsight.risk.events.detected{type,severity}` per detection;
  `finsight.risk.events.processed` counts every consumed event.

### The monetary bar is per person

The four amount-based rules are **not** judged against a platform-wide constant. Each draws its
threshold from the user's own history:

```
bar = enough history ? max(their mean × 5, flat ÷ 10) : flat
```

- **their mean** — `avg(amount)` over the user's prior observations of the same transaction type,
  strictly before this event (the triggering event never feeds its own bar). For the daily rules
  it is the mean of their prior **daily totals**: total ÷ the number of distinct days they
  transacted on, so quiet days do not drag the bar down.
- **enough history** — at least **10** prior observations (prior *days*, for the daily rules).
  Below that, the flat threshold applies unchanged, so a new user is protected from their first
  transaction rather than from their tenth.
- **the floor** (`flat ÷ 10`) stops a user whose whole financial life is small amounts from
  getting a HIGH-severity alert over a cup of coffee.

Why: a flat 10,000,000 is noise to someone who spends that weekly and silence to someone whose
largest ever expense is a tenth of it. The count-based rules stay absolute — five transactions in
ten minutes is a shape of behaviour, not an amount, and does not scale with wealth.

The **5×** multiple deliberately sits above the anomaly detector's 3× (see
[Anomaly Detection](#anomaly-detection)): the two are tiers of one idea, and at the same multiple
they would be one rule wearing two names. 3× is worth recording; 5× is worth interrupting someone
over.

| Rule | Trigger condition | Severity |
|---|---|---|
| **HIGH_AMOUNT_EXPENSE** | This EXPENSE's `amount` ≥ the user's expense bar (flat **10,000,000**; floor **1,000,000**). | HIGH |
| **RAPID_SPENDING** | This event is the **5th** EXPENSE for the user within a **10-minute** window (`count == 5`) — fires once per burst, not on every later event. | MEDIUM |
| **LARGE_DAILY_SPEND** | This event pushes the user's EXPENSE total for the calendar day from ≤ their daily bar to > it (flat **20,000,000**; floor **2,000,000**) — a single crossing per day. | HIGH |

The INCOME family is symmetric. Its flat thresholds sit higher than the expense ones because a
salary is legitimately large and reusing the expense numbers would alert on every payday — but for
an established user the bar comes from their own income history, so the flat figures matter only
during cold start.

| Rule | Trigger condition | Severity |
|---|---|---|
| **HIGH_AMOUNT_INCOME** | This INCOME's `amount` ≥ the user's income bar (flat **50,000,000**; floor **5,000,000**). | MEDIUM |
| **RAPID_INCOME** | This event is the **5th** INCOME for the user within a **10-minute** window. | MEDIUM |
| **LARGE_DAILY_INCOME** | This event pushes the user's INCOME total for the calendar day from ≤ their daily income bar to > it (flat **100,000,000**; floor **10,000,000**). | MEDIUM |
| **INCOME_SPIKE** | This INCOME ≥ **3×** the user's mean prior income, once **10** prior incomes exist. Already relative before the change above — it is the pattern the other rules were brought in line with. | HIGH |

### Recurring charges (Phase G.1)

A **series** is a charge that keeps coming back: a subscription, a rent payment, a standing bill.
`TransactionCreated` carries no merchant and no description, so a series can only be identified by
**(user, category, currency, roughly this amount) repeating on a cadence** — the cadence
requirement is what stops it firing on ordinary shopping. Two unrelated charges of similar size in
one category will be read as one series; that is a limit of the event contract, not a modelling
choice.

State lives in `recurring_series` (`RecurringDetector`, `RecurringSweeper`). Recognised cadences
are **weekly (7 ±2 days)**, **monthly (30 ±5)** and **quarterly (91 ±10)** — monthly is ±5 because
calendar months run 28–31 days and billing dates slip over weekends. The bands do not overlap.

Three tolerances, deliberately different:

| Tolerance | Value | What it decides |
|---|---|---|
| Seed | **10%** | Whether two charges are the same charge, and so open a series. Tight: this is the weakest evidence in the feature. |
| Match | **25%** | Whether a new charge belongs to a series that exists. Wider than the rise threshold on purpose — otherwise a subscription that went up in price would stop matching its own series and silently seed a second one instead of being reported. |
| Rise | **1.15×** | What counts as a price increase worth interrupting someone over. A drop to ≤ **0.85×** re-bases the price silently — a charge getting cheaper is not a risk. |

`typical_amount` is the **established** price, moved only when a change is flagged (or is a drop),
so a subscription creeping up 5% a month is still measured against what it originally cost.

| Rule | Trigger condition | Severity |
|---|---|---|
| **RECURRING_CHARGE_DETECTED** | The **3rd** charge matched to a series. Two charges one interval apart could be coincidence; three is a pattern. | LOW |
| **RECURRING_PRICE_INCREASE** | A charge on an established series (4th onwards) ≥ **1.15×** its established price. Fires once per rise — the series re-bases to the new price. | MEDIUM |
| **RECURRING_CHARGE_MISSED** | An established series whose `next_expected` passed more than **3 days** ago (`RecurringSweeper`, hourly). The series is then marked `LAPSED` so it is reported once, not every hour. A charge that resumes later opens a fresh series. | LOW |

The missed-charge alert carries the series' **last** transaction id: there is no triggering
transaction for an absence, and "the charge that should have repeated" is the useful thing to
point at. Note it cannot distinguish a cancelled subscription from a failed payment — hence LOW.

- **Read API:** `GET /api/v1/recurring` (internal, like the other three).
- **Config:** `finsight.recurring.{sweep-ms,sweep-initial-delay-ms,grace-days}`.
- **Single instance**, like the notification digest scheduler: two sweepers would each claim the
  same overdue series and alert twice.

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

> **`BUDGET_RISK` is "approaching", not "over".** Actually exceeding a budget is not detected here
> at all: budget-service owns `spent_amount` and publishes `BudgetExceeded` itself (see
> [event-catalog](event-catalog.md#3-budgetexceeded)), which becomes a user-facing notification.
> The split is deliberate — this insight is an advisory signal computed from risk-service's own
> eventually-consistent read-model, while an over-budget alert has to agree with the figure the
> Budgets page renders, and only budget-service knows that one.

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

## Monthly report (Phase G.2)

The month in review, produced by **analytics-service** rather than risk-service: the figures live
in `analytics_db` and nowhere else. A daily sweep (`MonthlyReportScheduler`) publishes
`MonthlyReportReady` on `finsight.reports.monthly` once per user per month, carrying the finished
numbers — income, expense, net, savings rate, largest expense category — because
notification-service cannot read `analytics_db` and must not call analytics-service at runtime.

- **Why a sweep:** "the month ended" is not an event any service publishes. The sweep always
  targets the *previous* calendar month, and `monthly_report_sent` makes every later run that day
  a no-op — which also makes it self-healing when the box was down on the 1st.
- **Ordering:** the sent-row is written **before** the publish. A crash between them costs one
  user one report; the other order would re-send to everyone, every day, until it succeeded.
- **Delivery:** notification-service turns it into a `MONTHLY_REPORT` notification at severity
  `LOW` (nothing is wrong in a summary) and it leaves through whichever channels the user has on —
  bell, SSE, web push, email, webhook — including the digest.
- **Config:** `finsight.report.monthly.cron` (default `0 20 3 * * *`). **Single instance.**

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
| `finsight.analytics.reports.published` | – | monthly reports published (analytics-service, Phase G.2) |

The **FinSight Risk** Grafana dashboard visualizes `finsight.risk.events.detected` by type and
severity. (There is no dedicated insights/anomaly dashboard — out of scope for this phase.)
