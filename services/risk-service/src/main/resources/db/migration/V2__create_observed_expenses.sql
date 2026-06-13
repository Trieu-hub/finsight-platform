-- Observed EXPENSE transactions (Phase D.4). The windowed rules (RAPID_SPENDING,
-- LARGE_DAILY_SPEND) need to look across a user's recent transactions, so each consumed
-- EXPENSE is recorded here and the rules are evaluated with SQL count/sum over a window.
-- This keeps risk-service within its existing architecture (Kafka consumer + MySQL); no
-- stream-processing state store is introduced.
--
-- id is the source TransactionCreated eventId, giving idempotency: a redelivered event is
-- not double-counted (which would inflate the windowed aggregates). transaction_id is an
-- opaque reference (not a FK). Retention/cleanup of old rows is out of scope for D.4.
CREATE TABLE IF NOT EXISTS observed_expenses (
    id               CHAR(36)       NOT NULL,
    user_id          BIGINT         NOT NULL,
    amount           DECIMAL(19, 4) NOT NULL,
    -- Event time (the transaction's occurredAt) — basis for the 10-minute window.
    occurred_at      DATETIME(6)    NOT NULL,
    -- Calendar day (the transaction's transactionDate) — basis for the daily total.
    transaction_date DATE           NOT NULL,
    CONSTRAINT pk_observed_expenses PRIMARY KEY (id),
    -- Backs the RAPID_SPENDING count over [occurredAt-10m, occurredAt].
    KEY idx_observed_user_occurred (user_id, occurred_at),
    -- Backs the LARGE_DAILY_SPEND sum for a (user, day).
    KEY idx_observed_user_date (user_id, transaction_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
