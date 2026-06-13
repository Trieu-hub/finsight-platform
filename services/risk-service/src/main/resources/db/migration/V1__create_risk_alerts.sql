-- Persisted risk alerts (Phase D.2). One row per RiskDetected event the rule engine
-- produces. risk-service owns this schema inside risk_db; the row is the durable record
-- of a detection, while the RiskDetected Kafka event is the (best-effort) notification.
--
-- transaction_id is an opaque reference to a transaction-service transaction (not a FK:
-- it lives in another service's database). No idempotency key: a redelivered
-- TransactionCreated yields a fresh detection with a new id, so duplicate rows are
-- possible by design (the at-least-once tradeoff carried from Phase D.1).
CREATE TABLE IF NOT EXISTS risk_alerts (
    id             CHAR(36)    NOT NULL,
    user_id        BIGINT      NOT NULL,
    transaction_id CHAR(36)    NOT NULL,
    risk_type      VARCHAR(50) NOT NULL,
    risk_severity  VARCHAR(20) NOT NULL,
    -- When the risk was detected (the event's occurredAt).
    occurred_at    DATETIME(6) NOT NULL,
    -- When this row was persisted.
    created_at     DATETIME(6) NOT NULL,
    CONSTRAINT pk_risk_alerts PRIMARY KEY (id),
    -- Indexes backing "list a user's alerts" and "find alerts for a transaction".
    KEY idx_risk_alerts_user_id (user_id),
    KEY idx_risk_alerts_transaction_id (transaction_id),
    KEY idx_risk_alerts_occurred_at (occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
