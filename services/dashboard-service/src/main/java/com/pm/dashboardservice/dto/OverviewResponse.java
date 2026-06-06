package com.pm.dashboardservice.dto;

import com.pm.dashboardservice.client.dto.BudgetDto;
import com.pm.dashboardservice.client.dto.MonthlySummaryDto;
import com.pm.dashboardservice.client.dto.UserProfileDto;

import java.util.List;

/**
 * Landing view: the caller's profile, current-month income/expense/balance, and their
 * budget definitions. {@code profile} is null if the user has not created one yet.
 */
public record OverviewResponse(
        UserProfileDto profile,
        MonthlySummaryDto currentMonth,
        List<BudgetDto> budgets) {
}
