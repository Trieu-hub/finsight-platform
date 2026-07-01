-- MVP completion: introduce the TRANSFER transaction type (wallet-to-wallet moves).
-- TRANSFER is neither income nor expense. Downstream consumers (budget/risk/analytics)
-- already ignore any non-INCOME/EXPENSE type by design (their consumer-side event uses a
-- String `type` precisely so an unknown future type is skipped, not a deserialize error),
-- so widening the type here is backward compatible with the whole event backbone.

-- 1. Widen the type CHECK constraints on both tables to allow TRANSFER.
--    MySQL 8 enforces CHECK constraints; drop-then-add to replace the allowed set.
ALTER TABLE categories   DROP CHECK chk_categories_type;
ALTER TABLE categories   ADD CONSTRAINT chk_categories_type
    CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER'));

ALTER TABLE transactions DROP CHECK chk_transactions_type;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_type
    CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER'));

-- 2. Destination wallet for a transfer. The source is the existing `wallet_id`.
--    Opaque id, exactly like `wallet_id`: NOT a foreign key and NOT validated cross-domain
--    (there is deliberately no Wallet domain — consistent with the charter's scaffolded wallet).
ALTER TABLE transactions ADD COLUMN to_wallet_id BIGINT NULL AFTER wallet_id;

-- 3. Seed a protected system "Transfer" category so TRANSFER transactions satisfy the
--    existing NOT NULL + type-matched category rule uniformly (no special-casing in code).
--    Id 11 is the next seeded id; AUTO_INCREMENT continues from 12+. IGNORE keeps it idempotent.
INSERT IGNORE INTO categories (id, name, type, is_system) VALUES
    (11, 'Transfer', 'TRANSFER', TRUE);
