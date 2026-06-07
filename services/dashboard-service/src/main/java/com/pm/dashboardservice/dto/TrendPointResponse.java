package com.pm.dashboardservice.dto;

import java.math.BigDecimal;

/**
 * One point of the dashboard trend series. {@code period} is a year-month ("2026-06")
 * when granularity=month, or an ISO date ("2026-06-07") when granularity=day. Money
 * fields are summed over the period; {@code balance = income - expense}. Periods with no
 * activity are absent (the series is sparse — no zero-filling).
 */
public record TrendPointResponse(
        String period,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance) {
}
