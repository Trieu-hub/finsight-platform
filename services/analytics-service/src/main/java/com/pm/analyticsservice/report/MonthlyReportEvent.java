package com.pm.analyticsservice.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code MonthlyReportReady} integration event — published to
 * {@code finsight.reports.monthly} once per user per month (Phase G.2).
 *
 * <p>It carries the finished figures rather than a pointer to them. notification-service has no
 * access to {@code analytics_db} and must not call this service at runtime, so an event that
 * said only "user 42's report is ready" would be unusable at the other end.
 *
 * <p>Same envelope as every other event here ({@code eventId}/{@code eventType}/
 * {@code occurredAt}, ISO-8601 string timestamp, no Jackson type headers), and the
 * {@code eventId} is what the consumer's inbox de-duplicates on.
 */
public record MonthlyReportEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        Long userId,
        /* The month reported on, 'YYYY-MM'. */
        String periodMonth,
        String currency,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net,
        /* Share of income kept, e.g. 32.5 for "saved 32.5% of what came in". */
        double savingsRate,
        /* The category with the largest expense that month; null when there was none. */
        String topCategory,
        BigDecimal topCategoryAmount
) {

    /** Stable discriminator carried in {@link #eventType()}. */
    public static final String EVENT_TYPE = "MonthlyReportReady";

    /** Builds an event, stamping a fresh {@code eventId} and the current {@code occurredAt}. */
    public static MonthlyReportEvent of(Long userId, String periodMonth, String currency,
                                        BigDecimal income, BigDecimal expense, BigDecimal net,
                                        double savingsRate, String topCategory,
                                        BigDecimal topCategoryAmount) {
        return new MonthlyReportEvent(
                UUID.randomUUID(), EVENT_TYPE, Instant.now().toString(), userId, periodMonth,
                currency, income, expense, net, savingsRate, topCategory, topCategoryAmount);
    }
}
