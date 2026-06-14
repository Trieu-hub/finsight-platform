-- Record INCOME alongside EXPENSE in observed_expenses (Phase E.3) so the LOW_SAVINGS_RATE
-- insight can compare a user's monthly income against their monthly expenses from the same
-- data source — no new table, no new ingestion path, reusing the read-model already fed by
-- the Kafka consumer.
--
-- transaction_type discriminates the two. It is NOT NULL with a default of 'EXPENSE' so every
-- row recorded before this migration (all of which are expenses) is correctly back-filled and
-- the existing risk/insight aggregates, which now filter transaction_type = 'EXPENSE', keep
-- their exact prior behaviour.
ALTER TABLE observed_expenses
    ADD COLUMN transaction_type VARCHAR(20) NOT NULL DEFAULT 'EXPENSE' AFTER user_id;

-- Backs the per-(user, month) income and expense sums of LOW_SAVINGS_RATE, and keeps the
-- existing expense aggregates selective now that income shares the table.
CREATE INDEX idx_observed_user_type_date ON observed_expenses (user_id, transaction_type, transaction_date);
