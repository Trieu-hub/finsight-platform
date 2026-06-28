package com.pm.analyticsservice.dto;

import java.math.BigDecimal;

/**
 * Simple run-rate forecast for a month: the expense booked so far, scaled by how much of
 * the month has elapsed, to a projected month-end total. For a completed (past) month the
 * projection equals the actual; for a future month it is zero.
 */
public record ForecastResponse(
        String yearMonth,
        String currency,
        BigDecimal expenseToDate,
        int dayOfMonth,
        int daysInMonth,
        BigDecimal projectedExpense,
        BigDecimal dailyAverage
) {
}
