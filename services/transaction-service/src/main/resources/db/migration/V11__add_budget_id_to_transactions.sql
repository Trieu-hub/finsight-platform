-- Record which budget the user chose to charge an EXPENSE against, so budget-service can deduct
-- from exactly that budget instead of every budget matching the category (which double-counted
-- when two budgets shared one category). Opaque reference to budget-service's UUID key — no FK,
-- cross-service — mirroring how transactions already reference wallet ids. Nullable: INCOME,
-- TRANSFER and budget-less expenses (e.g. the game) carry no budget.
ALTER TABLE transactions ADD COLUMN budget_id CHAR(36) NULL AFTER category_id;
