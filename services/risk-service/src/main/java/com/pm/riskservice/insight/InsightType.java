package com.pm.riskservice.insight;

/**
 * The behavioral insights risk-service generates. The constant name is the {@code insight_type}
 * persisted and the {@code type} metric tag.
 *
 * <ul>
 *   <li>{@code SPENDING_INCREASE} (Phase E.1) — current month total &ge; previous month +30%.</li>
 *   <li>{@code CATEGORY_SURGE} (Phase E.2) — current month total in a category &ge; previous
 *       month in that category +50%.</li>
 *   <li>{@code BUDGET_RISK} (Phase E.2) — a budget's utilization exceeds 80% while its period
 *       is still open.</li>
 *   <li>{@code LOW_SAVINGS_RATE} (Phase E.3) — for a month with positive income, expenses reach
 *       at least 80% of that income.</li>
 * </ul>
 */
public enum InsightType {
    SPENDING_INCREASE,
    CATEGORY_SURGE,
    BUDGET_RISK,
    LOW_SAVINGS_RATE
}
