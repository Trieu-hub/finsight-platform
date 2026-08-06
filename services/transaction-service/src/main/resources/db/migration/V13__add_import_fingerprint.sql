-- Lets a re-uploaded statement be recognised as one already imported, rather than doubling every
-- row. The fingerprint is a SHA-256 of the fields a bank line is identified by (type, amount,
-- currency, date, description) — derived in the service, never sent by the client, so it cannot be
-- spoofed into skipping someone else's row.
--
-- NULL for everything recorded by hand or by the game: only imported rows carry one, and MySQL
-- allows any number of NULLs under a unique index. The index is the actual guard (a double-clicked
-- Import cannot slip a second copy past a check-then-insert race); the service's pre-check exists
-- to report a duplicate as "skipped" instead of an error.
--
-- Soft-deleting a transaction clears this column, so deleting an imported row and importing the
-- statement again brings it back — the alternative would be a row the user can neither see nor
-- ever re-import.
ALTER TABLE transactions ADD COLUMN import_fingerprint CHAR(64) NULL AFTER metadata;

CREATE UNIQUE INDEX uq_transactions_user_import_fingerprint
    ON transactions (user_id, import_fingerprint);
