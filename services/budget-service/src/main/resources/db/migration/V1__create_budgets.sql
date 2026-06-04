-- Budget definitions. The budget domain is self-contained: it stores user spending
-- limits per category/period. It does NOT own categories (only an opaque category_id
-- reference, like transactions reference wallet_id) and does NOT compute spend — that
-- composition belongs to a future dashboard/BFF reading transaction-service summaries.
CREATE TABLE IF NOT EXISTS budgets (
    id           CHAR(36)       NOT NULL,
    user_id      BIGINT         NOT NULL,
    name         VARCHAR(100),
    -- Opaque reference to a transaction-service category. Intentionally not a FK:
    -- categories live in another service, so cross-service constraints are avoided.
    category_id  BIGINT         NOT NULL,
    period_type  VARCHAR(20)    NOT NULL,
    start_date   DATE           NOT NULL,
    end_date     DATE           NOT NULL,
    limit_amount DECIMAL(19, 4) NOT NULL,
    currency     VARCHAR(3)     NOT NULL,
    is_deleted   BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at   DATETIME(6),
    updated_at   DATETIME(6),
    CONSTRAINT pk_budgets PRIMARY KEY (id),
    CONSTRAINT chk_budgets_period_type CHECK (period_type IN ('MONTHLY', 'WEEKLY', 'YEARLY', 'CUSTOM')),
    CONSTRAINT chk_budgets_limit_amount CHECK (limit_amount > 0),
    CONSTRAINT chk_budgets_date_range CHECK (end_date >= start_date),
    -- Indexes backing "fetch budgets by user with filters + pagination".
    -- Declared inline so the whole table (indexes included) is covered by IF NOT EXISTS.
    -- Duplicate-budget uniqueness is enforced in the service layer over non-deleted
    -- rows (a hard UNIQUE constraint would block re-creating a budget after soft-delete).
    KEY idx_budgets_user_id (user_id),
    KEY idx_budgets_user_category (user_id, category_id),
    KEY idx_budgets_user_dates (user_id, start_date, end_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
