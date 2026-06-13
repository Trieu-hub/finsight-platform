-- Generalize the insights dedup key (Phase E.2) so one table holds all three insight types.
--
-- Phase E.1 enforced one insight per (user, type, period_month) — fine when SPENDING_INCREASE
-- was the only type. E.2 adds CATEGORY_SURGE (one per category per month) and BUDGET_RISK
-- (one per budget). subject_id is the per-type scope discriminator:
--   SPENDING_INCREASE -> '-'              (one per user/month)
--   CATEGORY_SURGE    -> the category id  (one per user/month/category)
--   BUDGET_RISK       -> the budget id    (one per budget)
-- It is NOT NULL with a sentinel default so the unique key treats every type uniformly (a
-- nullable column would let MySQL's "NULLs are distinct" rule defeat the constraint).
--
-- category_id is informational for the API (the surging/at-risk category); null for
-- SPENDING_INCREASE.
ALTER TABLE insights
    ADD COLUMN category_id BIGINT      NULL        AFTER period_month,
    ADD COLUMN subject_id  VARCHAR(64) NOT NULL DEFAULT '-' AFTER category_id;

-- Replace the month-scoped unique with one that includes the subject discriminator.
ALTER TABLE insights DROP INDEX uq_insights_user_type_month;
ALTER TABLE insights
    ADD CONSTRAINT uq_insights_user_type_month_subject
        UNIQUE (user_id, insight_type, period_month, subject_id);
