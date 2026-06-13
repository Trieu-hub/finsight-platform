-- Local read-model of budget definitions (Phase E.2), maintained by consuming
-- finsight.budgets.changed from budget-service. risk-service does NOT call budget-service at
-- runtime; it keeps just enough budget state (limit + category + currency + window) to compute
-- utilization for the BUDGET_RISK insight, using the spend already in observed_expenses.
--
-- id is the source budget id, so a BudgetChanged event upserts its row (idempotent under
-- redelivery). deleted mirrors budget-service's soft delete; matching excludes deleted rows.
-- limit_amount/spent are not stored as a pair here — spend is summed live from observed_expenses
-- so the read-model never drifts from the events it actually saw.
CREATE TABLE IF NOT EXISTS budget_snapshots (
    id           CHAR(36)       NOT NULL,
    user_id      BIGINT         NOT NULL,
    category_id  BIGINT         NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    limit_amount DECIMAL(19, 4) NOT NULL,
    start_date   DATE           NOT NULL,
    end_date     DATE           NOT NULL,
    deleted      BOOLEAN        NOT NULL DEFAULT FALSE,
    updated_at   DATETIME(6)    NOT NULL,
    CONSTRAINT pk_budget_snapshots PRIMARY KEY (id),
    -- Backs the active-budget lookup for a (user, category, currency) on a transaction date.
    KEY idx_budget_user_cat_cur (user_id, category_id, currency)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
