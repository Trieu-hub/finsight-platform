package com.pm.analyticsservice.forecast;

import java.util.Arrays;

/**
 * A fitted daily-spend model: Holt's linear trend (level + slope) with a multiplicative
 * weekly season. Predicting a day is {@code (level + h * trend) * dowIndex[dayOfWeek]}.
 *
 * <p>This is a plain value object — no Spring, no persistence. {@link SeasonalTrendTrainer}
 * produces it from a series; {@link SpendingModel} stores it; the forecast reads it back.
 *
 * @param level      smoothed level at the end of the training window
 * @param trend      smoothed day-over-day slope
 * @param dowIndex   seven multiplicative indices, Monday first (index 0 == Monday, matching
 *                   {@code DayOfWeek.getValue() - 1}), normalised to average 1.0
 * @param sigma      standard deviation of the in-sample residuals, in currency units
 * @param sampleDays number of days in the training window
 */
public record SeasonalTrendModel(
        double level,
        double trend,
        double[] dowIndex,
        double sigma,
        int sampleDays
) {

    public static final int WEEK = 7;

    public SeasonalTrendModel {
        if (dowIndex == null || dowIndex.length != WEEK) {
            throw new IllegalArgumentException("dowIndex must hold exactly " + WEEK + " values");
        }
        // Defensive copy: the array is mutable and this record is handed around freely.
        dowIndex = dowIndex.clone();
    }

    @Override
    public double[] dowIndex() {
        return dowIndex.clone();
    }

    /**
     * Predicted spend {@code horizon} days after the end of the training window.
     *
     * @param horizon         1 == the first day after the window
     * @param dayOfWeekIndex  0 == Monday
     */
    public double predict(int horizon, int dayOfWeekIndex) {
        double base = level + horizon * trend;
        // Spend cannot go negative: a steep downward trend must flatten at zero rather
        // than subtracting from the month's projection.
        return Math.max(0.0, base) * dowIndex[Math.floorMod(dayOfWeekIndex, WEEK)];
    }

    @Override
    public String toString() {
        return "SeasonalTrendModel[level=%.2f, trend=%.4f, dow=%s, sigma=%.2f, days=%d]"
                .formatted(level, trend, Arrays.toString(dowIndex), sigma, sampleDays);
    }
}
