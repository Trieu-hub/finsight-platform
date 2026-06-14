-- Index backing the UNUSUAL_TRANSACTION_AMOUNT baseline lookup (PF-2). The anomaly's prior-
-- history query filters observed_expenses by (user_id, transaction_type = 'EXPENSE',
-- occurred_at < ?) on every consumed EXPENSE. The existing indexes don't match this predicate:
-- idx_observed_user_occurred (user_id, occurred_at) lacks transaction_type, and
-- idx_observed_user_type_date (user_id, transaction_type, transaction_date) ranges on the wrong
-- column. This composite matches it exactly, so the range scan is index-backed and stays cheap
-- as a user's history grows. Behaviour is unchanged — this only affects the access path.
CREATE INDEX idx_observed_user_type_occurred
    ON observed_expenses (user_id, transaction_type, occurred_at);
