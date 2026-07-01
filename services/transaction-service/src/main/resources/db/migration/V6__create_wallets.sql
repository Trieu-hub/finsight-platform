-- Full Wallet domain: a user's accounts, each holding a running balance in one currency.
-- Lives in the transaction domain so a transaction write can keep the balance correct
-- atomically (same DB transaction) — no cross-service call and no eventual-consistency drift.
CREATE TABLE IF NOT EXISTS wallets (
    id         BIGINT         NOT NULL AUTO_INCREMENT,
    user_id    BIGINT         NOT NULL,
    name       VARCHAR(100)   NOT NULL,
    type       VARCHAR(20)    NOT NULL DEFAULT 'CASH',
    currency   VARCHAR(3)     NOT NULL,
    balance    DECIMAL(19, 4) NOT NULL DEFAULT 0,
    is_deleted BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    CONSTRAINT pk_wallets PRIMARY KEY (id),
    CONSTRAINT chk_wallets_type CHECK (type IN ('CASH', 'BANK', 'CARD', 'SAVINGS', 'OTHER')),
    KEY idx_wallets_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- transactions.wallet_id / to_wallet_id stay OPAQUE references (no FK): they predate this table
-- and may hold ids from before wallets existed. Ownership + currency are enforced in the service
-- on every write, consistent with how budget-service treats categoryId as an opaque reference.
