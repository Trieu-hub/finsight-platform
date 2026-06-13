-- Extend observed_expenses (Phase E.2) so the category- and budget-scoped insights can be
-- derived from the same data. category_id backs CATEGORY_SURGE (per-category month-over-month)
-- and BUDGET_RISK (matching a budget's category); currency backs BUDGET_RISK's currency-exact
-- matching, mirroring budget-service's utilization rule.
--
-- Nullable because rows recorded before this migration (Phase D.4/E.1) carry neither; they
-- simply don't participate in the category/budget queries. All rows recorded from E.2 on
-- populate both from the source TransactionCreated event.
ALTER TABLE observed_expenses
    ADD COLUMN category_id BIGINT      NULL AFTER user_id,
    ADD COLUMN currency    VARCHAR(3)  NULL AFTER amount;

-- Backs the per-category month sum (CATEGORY_SURGE) and the budget-window sum (BUDGET_RISK).
CREATE INDEX idx_observed_user_cat_date ON observed_expenses (user_id, category_id, transaction_date);
