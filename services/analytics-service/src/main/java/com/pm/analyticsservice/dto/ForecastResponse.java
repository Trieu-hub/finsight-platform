package com.pm.analyticsservice.dto;

import java.math.BigDecimal;

/**
 * Month-end spend projection. For a completed (past) month the projection equals the actual;
 * for a future month it is zero.
 *
 * <p>{@code method} says which projection produced it, because the two answer differently and
 * a caller comparing figures across users deserves to know which one it is looking at:
 * <ul>
 *   <li>{@code RUN_RATE} — spend so far scaled by how much of the month has elapsed. Assumes
 *       every remaining day looks like the average day so far.</li>
 *   <li>{@code MODEL} — the remaining days predicted one at a time from the user's fitted
 *       {@code spending_model}, so a month ending on a weekend projects higher than one
 *       ending mid-week. Used only where a model has been trained; otherwise the run rate
 *       still answers.</li>
 * </ul>
 *
 * <p>{@code projectedLow}/{@code projectedHigh} bracket the projection at roughly one standard
 * deviation of the model's own residuals, and are {@code null} under {@code RUN_RATE}, which
 * has no error estimate to report.
 */
public record ForecastResponse(
        String yearMonth,
        String currency,
        BigDecimal expenseToDate,
        int dayOfMonth,
        int daysInMonth,
        BigDecimal projectedExpense,
        BigDecimal dailyAverage,
        String method,
        BigDecimal projectedLow,
        BigDecimal projectedHigh
) {
}
