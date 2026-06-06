package com.pm.dashboardservice.dto;

import java.math.BigDecimal;

/**
 * Aggregate budget usage for the window: total spend across budgeted categories vs the
 * sum of all budget limits. {@code utilizationPercent} is 0 when there are no limits.
 */
public record BudgetUtilization(
        BigDecimal totalLimit,
        BigDecimal totalSpent,
        BigDecimal utilizationPercent) {
}
