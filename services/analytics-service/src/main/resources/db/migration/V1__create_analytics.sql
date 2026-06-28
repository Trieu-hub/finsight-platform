-- analytics-service schema. Owns analytics_db.
--
-- A CQRS read model built from the TransactionCreated event stream
-- (finsight.transactions.created, owned by transaction-service). One row per
-- (user, month, category, type, currency) carries the running total and count, so the
-- read APIs (overview / categories / forecast / summary) never scan raw transactions.
CREATE TABLE IF NOT EXISTS monthly_category_rollup (
    id           CHAR(36)       NOT NULL,
    user_id      BIGINT         NOT NULL,
    -- Calendar month as 'YYYY-MM'; sorts chronologically as a plain string.
    -- (Named period_month, not year_month: YEAR_MONTH is a reserved word in MySQL.)
    period_month CHAR(7)        NOT NULL,
    -- 0 == uncategorized (the event may omit categoryId, which cannot sit in the
    -- composite uniqueness rule below).
    category_id  BIGINT         NOT NULL,
    type         VARCHAR(16)    NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    total_amount DECIMAL(18, 2) NOT NULL,
    txn_count    INT            NOT NULL,
    updated_at   DATETIME(6)    NOT NULL,
    CONSTRAINT pk_monthly_category_rollup PRIMARY KEY (id),
    -- One slot per (user, month, category, type, currency): the upsert target.
    CONSTRAINT uq_rollup_slot UNIQUE (user_id, period_month, category_id, type, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- The read APIs all scope by user then month (or month range), so this index leads with
-- (user_id, period_month) and keeps every query bounded to one user's rows.
CREATE INDEX idx_rollup_user_month ON monthly_category_rollup (user_id, period_month);

-- Consumer-side idempotency inbox: one row per Kafka event already folded into the
-- rollup. Kafka delivers at-least-once, so a redelivered TransactionCreated must be
-- detected by its eventId and skipped instead of double-counting. The row is written in
-- the SAME transaction as the rollup upsert.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
