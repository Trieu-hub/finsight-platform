-- Categories are owned by the transaction domain (no cross-service coupling).
CREATE TABLE IF NOT EXISTS categories (
    id   BIGINT       NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20)  NOT NULL,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT chk_categories_type CHECK (type IN ('INCOME', 'EXPENSE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS transactions (
    id               CHAR(36)       NOT NULL,
    user_id          BIGINT         NOT NULL,
    type             VARCHAR(20)    NOT NULL,
    amount           DECIMAL(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    category_id      BIGINT         NOT NULL,
    description      VARCHAR(500),
    transaction_date DATE           NOT NULL,
    wallet_id        BIGINT,
    is_deleted       BOOLEAN        NOT NULL DEFAULT FALSE,
    metadata         JSON,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT fk_transactions_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_transactions_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0),
    -- Mandatory indexes optimizing "fetch transactions by user with filters + pagination".
    -- Declared inline so the whole table (indexes included) is covered by IF NOT EXISTS.
    KEY idx_transactions_user_id (user_id),
    KEY idx_transactions_user_date (user_id, transaction_date DESC),
    KEY idx_transactions_user_type (user_id, type),
    KEY idx_transactions_category_id (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
