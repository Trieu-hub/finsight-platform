package com.pm.analyticsservice.service;

import com.pm.analyticsservice.dto.CategorySliceResponse;
import com.pm.analyticsservice.dto.ForecastResponse;
import com.pm.analyticsservice.dto.MonthlySummaryResponse;
import com.pm.analyticsservice.dto.OverviewResponse;

import java.util.List;

/**
 * Read-side analytics over the monthly rollup model. Every method is scoped to one user
 * and answers from the pre-aggregated rows — no raw-transaction scan, no call to another
 * service. {@code currency} is optional: when null the user's dominant currency for the
 * period is used.
 */
public interface AnalyticsService {

    OverviewResponse overview(Long userId, int year, int month, String currency);

    List<CategorySliceResponse> categories(Long userId, String fromYearMonth, String toYearMonth,
                                           String currency);

    ForecastResponse forecast(Long userId, int year, int month, String currency);

    MonthlySummaryResponse summary(Long userId, int year, int month, String currency);
}
