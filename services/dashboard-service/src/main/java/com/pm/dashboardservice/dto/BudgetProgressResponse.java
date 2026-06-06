package com.pm.dashboardservice.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Budget progress for a spend window. {@code fromDate}/{@code toDate} are the window the
 * spend was computed over (defaults to the current month when not supplied).
 */
public record BudgetProgressResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<BudgetProgressItem> items) {
}
