# FinSight — Event Catalog

_Last updated: 2026-06-14 · Source of truth: the producer-side event records under `services/`._

Every implemented Kafka event is listed here. Conventions shared by all three:

- **Serialization:** JSON, **no** Jackson type headers (language-neutral; consumers
  deserialize by the documented schema and a per-consumer default type).
- **Partition key:** `userId`, so a user's events stay strictly ordered on one partition.
- **Envelope:** `eventId` (UUID, for de-duplication), `eventType` (stable discriminator
  string), `occurredAt` (ISO-8601 instant string).
- **Temporals are strings** (`occurredAt`, dates) so the wire shape is independent of the
  producer's Jackson date configuration.
- **Delivery:** at-least-once; published `AFTER_COMMIT`. Consumers are idempotent.

| Event | Topic | Producer | Consumer(s) |
|---|---|---|---|
| `TransactionCreated` | `finsight.transactions.created` | transaction-service | budget-service, risk-service |
| `BudgetChanged` | `finsight.budgets.changed` | budget-service | risk-service |
| `RiskDetected` | `finsight.risk.detected` | risk-service | _(none yet)_ |

---

## 1. TransactionCreated

- **Topic:** `finsight.transactions.created`
- **Producer:** transaction-service — `TransactionCreatedEvent`, published by
  `KafkaTransactionEventPublisher` from `TransactionEventListener` (AFTER_COMMIT of
  `TransactionServiceImpl.create()`).
- **Consumers:**
  - budget-service (`TransactionEventConsumer`, group `budget-service`) — materializes
    `spent_amount` for matching budgets (EXPENSE only).
  - risk-service (`RiskEventConsumer`, group `risk-service`) — records `observed_expenses`
    and evaluates risk rules, insights, and the anomaly rule.
- **Purpose:** the platform's primary fact — a transaction was persisted. The backbone the
  budget-utilization, risk, insight, and anomaly features are derived from.

**Payload**

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | de-dup key |
| `eventType` | string | `"TransactionCreated"` |
| `occurredAt` | string | ISO-8601 instant (publish time) |
| `transactionId` | UUID | the persisted transaction |
| `userId` | number (Long) | partition key |
| `type` | string | `INCOME` or `EXPENSE` (consumers treat it as a plain string) |
| `amount` | number (BigDecimal) | |
| `currency` | string | ISO 4217 |
| `categoryId` | number (Long) | |
| `transactionDate` | string | ISO date (`YYYY-MM-DD`); may be null |
| `walletId` | number (Long) | scaffolded; no TRANSFER semantics yet |

```json
{
  "eventId": "0f9c2a1e-7b3d-4a9b-9d2e-0a1b2c3d4e5f",
  "eventType": "TransactionCreated",
  "occurredAt": "2026-06-14T10:15:30.123Z",
  "transactionId": "11111111-2222-3333-4444-555555555555",
  "userId": 4242,
  "type": "EXPENSE",
  "amount": 125000.00,
  "currency": "USD",
  "categoryId": 7,
  "transactionDate": "2026-06-14",
  "walletId": 1
}
```

---

## 2. BudgetChanged

- **Topic:** `finsight.budgets.changed`
- **Producer:** budget-service — `BudgetChangedEvent`, published by `KafkaBudgetEventPublisher`
  from `BudgetEventListener` (AFTER_COMMIT of a budget create / update / soft-delete).
- **Consumer:** risk-service (`BudgetEventConsumer`, dedicated listener container factory) —
  upserts a local `budget_snapshots` read-model keyed by `budgetId` (idempotent,
  last-write-wins) used by the BUDGET_RISK insight.
- **Purpose:** propagate budget lifecycle changes so risk-service can evaluate budget
  utilization without calling budget-service synchronously.

**Payload**

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | de-dup key |
| `eventType` | string | `"BudgetChanged"` |
| `occurredAt` | string | ISO-8601 instant |
| `budgetId` | UUID | read-model upsert key |
| `userId` | number (Long) | partition key |
| `categoryId` | number (Long) | |
| `currency` | string | ISO 4217 |
| `limitAmount` | number (BigDecimal) | |
| `startDate` | string | ISO date |
| `endDate` | string | ISO date |
| `periodType` | string | `MONTHLY` / `WEEKLY` / `YEARLY` / `CUSTOM` (plain string for forward-compat) |
| `deleted` | boolean | `true` ⇒ soft-deleted; consumers stop matching it |

```json
{
  "eventId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "eventType": "BudgetChanged",
  "occurredAt": "2026-06-14T09:00:00.000Z",
  "budgetId": "99999999-8888-7777-6666-555555555555",
  "userId": 4242,
  "categoryId": 7,
  "currency": "USD",
  "limitAmount": 1000000.00,
  "startDate": "2026-06-01",
  "endDate": "2026-06-30",
  "periodType": "MONTHLY",
  "deleted": false
}
```

---

## 3. RiskDetected

- **Topic:** `finsight.risk.detected`
- **Producer:** risk-service — `RiskDetectedEvent`, published by `RiskEventConsumer` for each
  risk rule that fires. The detection is also persisted to `risk_alerts` (the durable record);
  the event is the best-effort notification.
- **Consumer:** _none today._ The topic exists for a future Notification Service.
- **Purpose:** announce a detected risk. Kept intentionally minimal; `riskType`/`riskSeverity`
  are plain strings so new rules don't break consumers.

**Payload**

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | also the `risk_alerts` row id |
| `eventType` | string | `"RiskDetected"` |
| `occurredAt` | string | ISO-8601 instant |
| `userId` | number (Long) | partition key |
| `transactionId` | UUID | the triggering transaction |
| `riskType` | string | `HIGH_AMOUNT_EXPENSE` / `RAPID_SPENDING` / `LARGE_DAILY_SPEND` |
| `riskSeverity` | string | `HIGH` / `MEDIUM` (see [intelligence.md](intelligence.md)) |

```json
{
  "eventId": "cccccccc-dddd-eeee-ffff-000011112222",
  "eventType": "RiskDetected",
  "occurredAt": "2026-06-14T10:15:31.000Z",
  "userId": 4242,
  "transactionId": "11111111-2222-3333-4444-555555555555",
  "riskType": "HIGH_AMOUNT_EXPENSE",
  "riskSeverity": "HIGH"
}
```

---

## Not an event

`SPENDING_INCREASE`, `CATEGORY_SURGE`, `BUDGET_RISK`, `LOW_SAVINGS_RATE` (insights) and
`UNUSUAL_TRANSACTION_AMOUNT` (anomaly) are **persisted records exposed over REST**, not Kafka
events — risk-service does not publish them. See [intelligence.md](intelligence.md).
