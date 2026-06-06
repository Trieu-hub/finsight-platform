package com.pm.dashboardservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One budget's spend-vs-limit for the requested window. {@code spentAmount} is the
 * user's EXPENSE total in the budget's category over the window; {@code remainingAmount}
 * may be negative when over budget.
 */
public record BudgetProgressItem(
        UUID budgetId,
        String name,
        Long categoryId,
        String periodType,
        LocalDate startDate,
        LocalDate endDate,
        String currency,
        BigDecimal limitAmount,
        BigDecimal spentAmount,
        BigDecimal remainingAmount,
        BigDecimal percentUsed) {
}
