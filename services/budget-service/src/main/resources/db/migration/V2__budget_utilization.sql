-- Budget utilization (Phase 2.2): spent_amount is an event-driven materialization
-- updated by the TransactionCreated Kafka consumer. It is eventually consistent and
-- can drift (no TransactionUpdated/TransactionDeleted events exist yet, and there is
-- no backfill for transactions created before the budget) — see docs/ADR-0004. The
-- dashboard's live computation over transaction-service summaries remains the
-- accurate view of spend.
ALTER TABLE budgets
    ADD COLUMN spent_amount DECIMAL(19, 4) NOT NULL DEFAULT 0;

-- Consumer-side idempotency inbox: one row per Kafka event already applied. Kafka
-- delivers at-least-once, so a redelivered event must be detected by its eventId and
-- skipped instead of double-counting into spent_amount. (This is an inbox/dedup
-- table, not a transactional outbox.)
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
