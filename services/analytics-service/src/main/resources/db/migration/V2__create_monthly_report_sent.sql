-- One row per monthly report already published (Phase G.2). The report is produced by a
-- scheduled sweep, not by an event, so nothing upstream carries an id to de-duplicate on:
-- this table is what stops the same month being reported twice when the sweep runs again
-- tomorrow, or when the service restarts mid-run.
--
-- The unique constraint is the backstop, not the mechanism — the scheduler checks first and
-- only one instance runs it. It exists because "we sent it twice" is an email in someone's
-- inbox, which cannot be taken back.
CREATE TABLE IF NOT EXISTS monthly_report_sent (
    id           CHAR(36)    NOT NULL,
    user_id      BIGINT      NOT NULL,
    -- The month reported on, 'YYYY-MM'. Named period_month to match monthly_category_rollup;
    -- YEAR_MONTH is a reserved word in MySQL.
    period_month CHAR(7)     NOT NULL,
    sent_at      DATETIME(6) NOT NULL,
    CONSTRAINT pk_monthly_report_sent PRIMARY KEY (id),
    CONSTRAINT uq_monthly_report_sent UNIQUE (user_id, period_month)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
