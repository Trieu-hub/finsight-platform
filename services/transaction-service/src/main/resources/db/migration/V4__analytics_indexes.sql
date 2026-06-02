-- Phase 3: composite indexes backing the analytics aggregate queries.
-- (user_id, transaction_date) already exists as idx_transactions_user_date.

-- Category breakdown: GROUP BY category for a user.
CREATE INDEX idx_transactions_user_category ON transactions (user_id, category_id);

-- Monthly summary / daily trend: user + type filtered over a date range.
CREATE INDEX idx_transactions_user_type_date ON transactions (user_id, type, transaction_date);
