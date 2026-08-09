-- Recurring charges (Phase G.1). A series is a charge that has repeated on a recognisable
-- cadence — weekly, monthly or quarterly — in the same category and currency for about the
-- same amount. It is derived from observed_expenses, the same read-model the risk rules,
-- insights and anomalies already use: no new ingestion, no ML, still deterministic.
--
-- The series is a stateful read-model rather than a query because two of the three signals
-- need it. "This charge went up" needs the price that was established before this event, and
-- "the charge that should have arrived did not" is the absence of an event, which no
-- event-driven rule can observe — a scheduled sweep reads next_expected instead.
--
-- Identity is (user, category, currency, roughly this amount). The source event carries no
-- merchant or description, so that is the whole of what a subscription can be recognised by
-- here; two different charges of similar size in one category will be treated as one series.
CREATE TABLE IF NOT EXISTS recurring_series (
    id                  CHAR(36)       NOT NULL,
    user_id             BIGINT         NOT NULL,
    category_id         BIGINT         NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    -- The established price. Updated only when a change is flagged (or is a drop), so slow
    -- creep is measured against the original price rather than against last month's.
    typical_amount      DECIMAL(19, 4) NOT NULL,
    -- The cadence in days: 7, 30 or 91.
    interval_days       INT            NOT NULL,
    -- How many charges have been matched to this series (starts at 2 — one interval).
    occurrences         INT            NOT NULL,
    first_seen          DATE           NOT NULL,
    last_seen           DATE           NOT NULL,
    -- last_seen + interval_days. The sweep flags a series whose expected date has passed.
    next_expected       DATE           NOT NULL,
    -- The most recent transaction matched to the series, so an alert raised by the sweep
    -- (where there is no triggering transaction) can still point at something real.
    last_transaction_id CHAR(36)       NOT NULL,
    -- ACTIVE while the charge keeps arriving; LAPSED once a sweep has reported it missing,
    -- which also stops the same series being reported every hour afterwards.
    status              VARCHAR(20)    NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    CONSTRAINT pk_recurring_series PRIMARY KEY (id),
    -- Backs the per-user read API and the match lookup on each consumed expense.
    KEY idx_recurring_series_user (user_id, category_id, currency),
    -- Backs the sweep, which scans by status and expected date across all users.
    KEY idx_recurring_series_sweep (status, next_expected)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Seeding a series looks for the previous expense of a similar amount in the same category
-- and currency. observed_expenses is indexed on (user_id, transaction_type, occurred_at),
-- which does not narrow by category, so that lookup would scan every expense the user has
-- ever made. This index matches the predicate and its transaction_date ordering.
CREATE INDEX idx_observed_user_type_category_date
    ON observed_expenses (user_id, transaction_type, category_id, transaction_date);
