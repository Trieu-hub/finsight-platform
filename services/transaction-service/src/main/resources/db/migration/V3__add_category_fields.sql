-- Phase 3: richer category metadata for management + analytics rendering.
ALTER TABLE categories
    ADD COLUMN icon      VARCHAR(50) NULL,
    ADD COLUMN color     VARCHAR(20) NULL,
    ADD COLUMN is_system BOOLEAN     NOT NULL DEFAULT FALSE;

-- The originally seeded categories are system defaults (protected from deletion).
UPDATE categories SET is_system = TRUE WHERE id BETWEEN 1 AND 10;

-- Let user-created categories receive generated ids. MySQL refuses to alter a column
-- referenced by a foreign key, so drop and re-create the FK around the change.
-- The seeded ids 1-10 are kept; AUTO_INCREMENT continues from the current maximum (11+).
ALTER TABLE transactions DROP FOREIGN KEY fk_transactions_category;

ALTER TABLE categories MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_category FOREIGN KEY (category_id) REFERENCES categories (id);
