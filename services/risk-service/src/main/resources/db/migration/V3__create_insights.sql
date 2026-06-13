-- Behavioral insights (Phase E.1). The first insight, SPENDING_INCREASE, is derived purely
-- from the observed_expenses data already recorded for the risk rules (Phase D.4) — no new
-- ingestion, no ML, no prediction. It flags a user whose current-month EXPENSE total exceeds
-- the previous month's by at least 30%.
--
-- One insight per (user, type, month): the unique key makes generation idempotent under
-- at-least-once event redelivery and stops a month being flagged twice. period_month is the
-- flagged (current) month as 'YYYY-MM'. previous_amount/current_amount/increase_pct are
-- snapshotted at generation time for display; they are not recomputed on read.
CREATE TABLE IF NOT EXISTS insights (
    id              CHAR(36)       NOT NULL,
    user_id         BIGINT         NOT NULL,
    insight_type    VARCHAR(50)    NOT NULL,
    -- The flagged month, 'YYYY-MM' (the month whose total increased).
    period_month    CHAR(7)        NOT NULL,
    previous_amount DECIMAL(19, 4) NOT NULL,
    current_amount  DECIMAL(19, 4) NOT NULL,
    -- Percent increase over the previous month, e.g. 45.00 for +45%.
    increase_pct    DECIMAL(9, 2)  NOT NULL,
    -- When the insight was generated (and persisted).
    generated_at    DATETIME(6)    NOT NULL,
    created_at      DATETIME(6)    NOT NULL,
    CONSTRAINT pk_insights PRIMARY KEY (id),
    -- Fire-once-per-month, per user, per insight type.
    CONSTRAINT uq_insights_user_type_month UNIQUE (user_id, insight_type, period_month),
    -- Backs the read API's newest-first listing.
    KEY idx_insights_generated_at (generated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
