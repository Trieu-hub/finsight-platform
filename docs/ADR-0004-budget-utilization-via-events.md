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
   redelivered event is detected and skipped — never double-counted. (This is an inbox
   dedup table, not a transactional outbox.)
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

## Accepted tradeoffs (deliberate, documented, revisitable)

- **`spent_amount` drifts.** Only `TransactionCreated` exists — there are no
  `TransactionUpdated`/`TransactionDeleted` events, so editing or soft-deleting a
  transaction does **not** adjust `spent_amount`. Emitting those events is the known
  fix and is out of scope for this phase.
- **No backfill.** A budget created *after* transactions occurred starts at 0; the
  consumer only applies events that arrive after it.
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
