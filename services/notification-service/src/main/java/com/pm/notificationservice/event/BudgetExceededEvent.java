package com.pm.notificationservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side view of budget-service's {@code BudgetExceeded} wire contract.
 *
 * <p>A deliberate copy, not a shared class — same stance as {@link RiskDetectedEvent}. The two
 * services own their schemas independently, and a compile-time link between them would make a
 * field rename in one a build break in the other.
 *
 * <p>Fields this service does not need are simply absent: the deserializer ignores unknown JSON
 * properties, so budget-service can add to the event without touching anything here.
 */
public record BudgetExceededEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID budgetId,
        Long userId,
        Long categoryId,
        String currency,
        BigDecimal limitAmount,
        BigDecimal spentAmount
) {
}
