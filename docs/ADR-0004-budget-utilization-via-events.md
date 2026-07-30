# ADR-0004 — Budget utilization via TransactionCreated events

- Status: **Accepted**
- Date: 2026-06-12
- Scope: budget-service's Kafka consumer (Phase 2.2) — the first event *consumer* in
  FinSight — and the `budgets.spent_amount` column it maintains.
- Relates to: ADR-0003 (dashboard BFF computes spent-vs-limit live), the
  `TransactionCreated` contract owned by transaction-service (Phase 2.1).

## Context

transaction-service publishes `TransactionCreated` to `finsight.transactions.created`
(Phase 2.1), but nothing consumed it: the event backbone was half a feature.
Separately, budget-service stores only budget *definitions*; spent-vs-limit progress
is computed live by dashboard-service joining transaction-service summaries (ADR-0003).

Completing the producer → broker → consumer flow with a budget utilization consumer is
the smallest end-to-end event-driven slice the platform can have. It also materializes
utilization (`spent_amount`) inside budget-service itself.

## Decision

1. **budget-service consumes `TransactionCreated`** (consumer group `budget-service`)
   and maintains `budgets.spent_amount` as an **event-driven materialization**.
2. **Matching rules** (all must hold): same `userId`, same `categoryId` (exact — no
   hierarchy), same `currency` (exact — no FX conversion), `transactionDate` within
   `[startDate, endDate]`, budget not soft-deleted. `periodType` is metadata and plays
   no role in matching. One transaction may match — and increments — **several
   overlapping budgets** (e.g. a MONTHLY and a YEARLY budget for the same category).
3. **EXPENSE only.** INCOME (and any future type, e.g. TRANSFER) is ignored, as are
   events without a parseable `transactionDate` or without an `eventId`.
4. **Idempotency inbox.** Kafka is at-least-once; each applied event's `eventId` is
   recorded in `processed_events` in the *same DB transaction* as the increment, so a
   redelivered event is detected and skipped — never double-counted. This is the *consumer*
   side of reliable messaging; the *producer* side is now a **transactional outbox** in
   transaction-service (the event is written to an `outbox` table in the same transaction as
   the transaction row, then a relay publishes it to Kafka). Outbox on produce + inbox on
   consume ⇒ effectively-once end to end, closing the former AFTER_COMMIT dual-write gap.
5. **Atomic SQL increment.** `spent_amount = spent_amount + :amount` in a single
   `UPDATE` across all matching budgets — never read-modify-write — so concurrent
   events cannot lose updates.
6. **Consumer-side contract copy.** budget-service declares its own
   `TransactionCreatedEvent` record (no shared library), deserializing the documented
   JSON schema exactly as a non-JVM consumer would. `type` is deliberately a `String`
   so unknown future types degrade to "ignored", not deserialization failures.
7. **Poison-message safety.** The JSON deserializer is wrapped in
   `ErrorHandlingDeserializer`; failures retry briefly (`DefaultErrorHandler`,
   3 attempts) then log-and-skip. No dead-letter topic at this scale.

## Update — 2026-07-03: transaction edits/deletes now reconciled

The first tradeoff below ("`spent_amount` drifts" on edit/delete) is **closed**.
transaction-service now emits two more lifecycle events and budget-service consumes them:

- **`TransactionUpdated`** (topic `finsight.transactions.updated`) carries the *old* and
  *new* snapshot. The consumer reverses the old contribution and applies the new one, so a
  category / amount / currency / date / EXPENSE↔INCOME edit is reflected in `spent_amount`.
- **`TransactionDeleted`** (topic `finsight.transactions.deleted`) carries the deleted
  snapshot. The consumer reverses that contribution.

Design notes that keep this consistent with the original decision:

- **Reversal is the inverse increment.** Both reuse `BudgetRepository.applyExpense` with a
  **negated** amount — still one atomic SQL `UPDATE`, never read-modify-write.
- **Order-independent.** The three events for one transaction travel on separate topics, so
  a delete/update can be processed before its create. Because the increment is additive,
  the *final* `spent_amount` is correct regardless of arrival order; a transient negative is
  possible and harmless (`spent_amount` has no non-negative CHECK). This preserves the
  "eventually consistent" contract rather than replacing it with strict ordering.
- **Same idempotency inbox.** Each event has its own `eventId`; update reverses **and**
  applies under a single `processed_events` row (both increments commit or neither does).
- **Dedicated topics, no blast radius.** risk-service and analytics-service consume only
  `finsight.transactions.created`, so they are unaffected — they do not see edits/deletes
  (risk/anomaly detections are point-in-time facts, not running totals, so this is correct).

Still open (unchanged below): budget-edit drift, retry-exhaustion loss, no backfill, and
the dashboard remaining the authoritative view.

## Update — 2026-07-24: attribution is by chosen budget, not category match

The original **Matching rules** (same `userId` + `categoryId` + `currency` + date-in-window,
incrementing *every* matching budget) **double-counted** when a user kept two budgets on one
category (e.g. "Ăn uống" and "Ăn vặt"): one expense hit both. That is now replaced.

- The user **picks the budget** when recording an EXPENSE. The chosen budget's id rides every
  transaction lifecycle event: `budgetId` on `TransactionCreated`/`TransactionDeleted`, and
  `oldBudgetId`/`newBudgetId` on `TransactionUpdated`.
- budget-service increments **exactly that one budget**, scoped to `userId` (so a spoofed
  budgetId belonging to another user is never charged) and not soft-deleted. `categoryId`,
  `currency` and `transactionDate` no longer take part in matching — they still ride the event
  for risk/analytics. A null/unknown `budgetId` (INCOME/TRANSFER, or a budget-less expense such
  as the game) charges nothing.
- Everything else is unchanged: still one atomic `UPDATE` (reversal negates the amount), still
  the `processed_events` inbox, still order-independent and eventually consistent, still no
  blast radius to risk/analytics. The **frontend** enforces the "an expense must name a budget"
  rule; the backend keeps `budgetId` nullable so internal producers (the game) are unaffected.
- Historic `spent_amount` computed under the old rule is not recomputed; only new events
  attribute by `budgetId`.

## Accepted tradeoffs (deliberate, documented, revisitable)

- **~~`spent_amount` drifts on transaction edit/delete.~~** *(Closed 2026-07-03 — see the
  Update above.)* Editing or soft-deleting a transaction now emits
  `TransactionUpdated` / `TransactionDeleted`, which budget-service reverses/re-applies.
- **Budget edits also drift.** Updating a budget's `categoryId`, `currency` or date
  window leaves `spent_amount` untouched, so after a slot change it reflects spend
  matched under the *old* slot. Resetting it to 0 on slot change would be equally
  wrong (the new window's past events are gone either way — see "no backfill").
  Accepted for the same reason as transaction-edit drift.
- **Retry exhaustion loses events.** A record that still fails after the error
  handler's retries (e.g. a DB outage outlasting ~3s) is logged and skipped — its
  offset commits and the event is never applied, silently widening drift. A DLT is
  the known fix and is deliberately out of scope; the skip is surfaced via the
  `finsight.budget.events.failed` counter (Phase C.2) rather than re-queued.
- **No backfill — almost.** A budget created *after* transactions occurred starts
  at 0; the consumer only applies events that arrive after it. One asymmetry: on the
  consumer group's *very first* start, `auto-offset-reset: earliest` replays whatever
  is still inside the topic's retention window, partially backfilling budgets that
  already exist at that moment. Correct behavior, called out so the "no backfill"
  rule is read precisely.
- **`processed_events` grows unboundedly.** One row per applied event, no TTL or
  purge. Irrelevant at this scale; a scheduled cleanup is documented debt, not built.
- **The dashboard remains the accurate view.** dashboard-service's live computation
  over transaction-service summaries (ADR-0003) is unaffected and authoritative;
  `spent_amount` is the eventually-consistent, event-driven approximation. The two
  views can disagree, and that is expected.

## Consequences

- The platform now has a complete producer → broker → consumer flow with verifiable
  end-to-end tests (Testcontainers KRaft broker + MySQL, real wire format).
- budget-service gains a runtime dependency on the Kafka broker (consumption only;
  HTTP CRUD works without it, and `finsight.kafka.enabled=false` disables the listener
  entirely — the same master-switch pattern as the producer side).
- budget-service's former scope statement ("does not compute spend") no longer holds;
  its CLAUDE.md is updated alongside this ADR.
