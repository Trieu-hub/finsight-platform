-- A client-supplied identity for one intended write, so retrying it cannot create a second row.
--
-- Needed by the offline outbox in the SPA: a transaction composed without a network is queued and
-- replayed later, and a replay whose response was lost (or that fires twice from two tabs) must
-- land as the SAME transaction, not as a duplicate the user then has to hunt down and delete.
--
-- Deliberately separate from import_fingerprint (V13). That column answers "which statement line
-- did this row come from" and is derived server-side from the row's own fields; this one is an
-- opaque token the client invents. Same mechanism, different meaning — folding them together
-- would make a re-imported CSV row and a replayed offline write indistinguishable in the data.
--
-- The unique index is what actually enforces it: the service checks first, but two concurrent
-- replays can both pass that check, and only the database can break the tie. NULL for everything
-- created the ordinary way, and MySQL allows unlimited NULLs under a unique index, so no existing
-- row can violate it.
ALTER TABLE transactions
    ADD COLUMN client_request_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_transactions_user_client_request
    ON transactions (user_id, client_request_id);
