package com.pm.notificationservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side copy of analytics-service's {@code MonthlyReportReady} wire contract (topic
 * {@code finsight.reports.monthly}). Deliberately duplicated rather than shared as a library,
 * exactly like {@link RiskDetectedEvent} and {@link BudgetExceededEvent}: each service owns its
 * view of the contract and deserializes by the documented schema.
 *
 * <p>The event carries the finished figures because this service cannot reach
 * {@code analytics_db} and must not call analytics-service at runtime.
 */
public record MonthlyReportEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        Long userId,
        String periodMonth,
        String currency,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net,
        double savingsRate,
        String topCategory,
        BigDecimal topCategoryAmount
) {
}
