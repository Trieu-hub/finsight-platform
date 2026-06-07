package com.pm.dashboardservice.dto;

import com.pm.dashboardservice.client.dto.UserProfileDto;

import java.util.List;

/**
 * Composite BFF view returned by {@code GET /api/v1/dashboard}: the caller's profile plus
 * all five feature blocks for one window. Assembled from upstream data fetched exactly once
 * per request (no duplicate calls). {@code profile} is null when the user has no profile yet;
 * the list blocks are empty (never null) when there is no data.
 */
public record DashboardResponse(
        UserProfileDto profile,
        DashboardSummaryResponse summary,
        List<BudgetProgressItem> budgetOverview,
        List<TopCategoryResponse> topCategories,
        List<RecentTransactionResponse> recentTransactions,
        List<TrendPointResponse> trend) {
}
