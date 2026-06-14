-- Detected anomalies (Phase F.1). The first and only type, UNUSUAL_TRANSACTION_AMOUNT, flags
-- an EXPENSE whose amount is at least 3× the user's average historical expense, once the user
-- has at least 10 prior EXPENSE transactions. Derived purely from observed_expenses (the same
-- read-model the risk rules and insights use) with a simple average — no ML, no statistical
-- model, no new ingestion.
--
-- id is the source TransactionCreated event id, giving idempotency: a redelivered event does
-- not produce a duplicate anomaly. amount is the flagged expense; average_amount and ratio
-- (amount / average) are snapshotted at detection time for display and are not recomputed on
-- read. transaction_id is an opaque reference to a transaction-service transaction (not a FK).
CREATE TABLE IF NOT EXISTS anomalies (
    id              CHAR(36)       NOT NULL,
    user_id         BIGINT         NOT NULL,
    transaction_id  CHAR(36)       NOT NULL,
    anomaly_type    VARCHAR(50)    NOT NULL,
    -- The flagged transaction amount.
    amount          DECIMAL(19, 4) NOT NULL,
    -- The user's average historical expense amount at detection time.
    average_amount  DECIMAL(19, 4) NOT NULL,
    -- amount / average_amount, e.g. 3.50 for "3.5× the average".
    ratio           DECIMAL(9, 2)  NOT NULL,
    -- When the anomaly was detected (the event's occurredAt).
    occurred_at     DATETIME(6)    NOT NULL,
    -- When this row was persisted.
    created_at      DATETIME(6)    NOT NULL,
    CONSTRAINT pk_anomalies PRIMARY KEY (id),
    KEY idx_anomalies_user_id (user_id),
    -- Backs the read API's newest-first listing.
    KEY idx_anomalies_occurred_at (occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
