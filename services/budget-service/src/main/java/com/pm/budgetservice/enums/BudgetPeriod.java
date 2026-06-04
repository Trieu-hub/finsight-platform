package com.pm.budgetservice.enums;

/**
 * The kind of period a budget covers. The explicit start/end dates on the budget
 * carry the actual range; this enum records intent and leaves room for future
 * recurrence logic (e.g. auto-generating next month's budget) without a schema change.
 */
public enum BudgetPeriod {
    MONTHLY,
    WEEKLY,
    YEARLY,
    CUSTOM
}
