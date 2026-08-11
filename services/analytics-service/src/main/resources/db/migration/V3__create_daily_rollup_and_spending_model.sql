-- Daily spend series + the fitted model built from it.
--
-- Why a second rollup: monthly_category_rollup collapses a whole month into one row, so a
-- weekly pattern ("Saturdays cost double") is not recoverable from it at any price. The
-- forecast model needs day granularity, and TransactionCreated already carries
-- transactionDate, so the same consumer can fold each event into both tables in one
-- transaction. This table starts empty and fills from the deploy forward: the raw history
-- lives in transaction_db, which this service must not read.
CREATE TABLE IF NOT EXISTS daily_category_rollup (
    id           CHAR(36)       NOT NULL,
    user_id      BIGINT         NOT NULL,
    spend_date   DATE           NOT NULL,
    -- 0 == uncategorized, matching monthly_category_rollup.
    category_id  BIGINT         NOT NULL,
    type         VARCHAR(16)    NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    total_amount DECIMAL(18, 2) NOT NULL,
    txn_count    INT            NOT NULL,
    updated_at   DATETIME(6)    NOT NULL,
    CONSTRAINT pk_daily_category_rollup PRIMARY KEY (id),
    CONSTRAINT uq_daily_rollup_slot UNIQUE (user_id, spend_date, category_id, type, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- The trainer reads one user's window in date order; the forecast reads one user's month.
CREATE INDEX idx_daily_rollup_user_date ON daily_category_rollup (user_id, spend_date);

-- One fitted model per (user, currency): Holt level + trend with seven multiplicative
-- day-of-week indices. Written by the nightly trainer, read by the forecast endpoint.
--
-- The seven indices are separate columns rather than JSON so the fit is inspectable with a
-- plain SELECT during an incident — the whole point of a deterministic model is that a human
-- can check what it believes.
CREATE TABLE IF NOT EXISTS spending_model (
    id           CHAR(36)       NOT NULL,
    user_id      BIGINT         NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    -- Smoothed daily level and day-over-day slope, in currency units.
    level_value  DECIMAL(18, 6) NOT NULL,
    trend_value  DECIMAL(18, 6) NOT NULL,
    -- Monday first, matching DayOfWeek.getValue() - 1. Normalised to average 1.0.
    dow_mon      DECIMAL(10, 6) NOT NULL,
    dow_tue      DECIMAL(10, 6) NOT NULL,
    dow_wed      DECIMAL(10, 6) NOT NULL,
    dow_thu      DECIMAL(10, 6) NOT NULL,
    dow_fri      DECIMAL(10, 6) NOT NULL,
    dow_sat      DECIMAL(10, 6) NOT NULL,
    dow_sun      DECIMAL(10, 6) NOT NULL,
    -- Residual standard deviation of the one-step-ahead predictions: the forecast's error bar.
    sigma        DECIMAL(18, 6) NOT NULL,
    -- Days in the training window, and the last day it covered.
    sample_days  INT            NOT NULL,
    trained_upto DATE           NOT NULL,
    trained_at   DATETIME(6)    NOT NULL,
    CONSTRAINT pk_spending_model PRIMARY KEY (id),
    CONSTRAINT uq_spending_model_slot UNIQUE (user_id, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
