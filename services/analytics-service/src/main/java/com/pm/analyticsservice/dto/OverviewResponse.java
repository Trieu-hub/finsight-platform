package com.pm.analyticsservice.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Month-over-month overview: this month's income/expense/net and savings rate, the same
 * for the prior month, the percentage changes, and the categories whose expense moved
 * the most. {@code *ChangePct} is null when the prior month's figure was zero.
 */
public record OverviewResponse(
        String yearMonth,
        String currency,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net,
        double savingsRate,
        BigDecimal prevIncome,
        BigDecimal prevExpense,
        BigDecimal prevNet,
        double prevSavingsRate,
        Double incomeChangePct,
        Double expenseChangePct,
        List<CategoryMover> topMovers
) {
}
