package com.pm.dashboardservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Headline KPIs for the window. {@code totalExpense} is all expense in the window;
 * {@code budgetUtilization.totalSpent} counts only expense in budgeted categories — the
 * two are intentionally different.
 */
public record DashboardSummaryResponse(
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal remainingBalance,
        BudgetUtilization budgetUtilization) {
}
