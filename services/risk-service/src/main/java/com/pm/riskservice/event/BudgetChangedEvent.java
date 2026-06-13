package com.pm.riskservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side copy of budget-service's {@code BudgetChanged} wire contract (topic
 * {@code finsight.budgets.changed}). Deliberately duplicated rather than shared as a library:
 * each service owns its view of the contract and deserializes by the documented schema (the
 * producer omits JSON type headers for the same reason).
 *
 * <p>Temporal/enum fields stay {@code String}s, mirroring the wire shape — an unknown future
 * {@code periodType} must not become a deserialization failure.
 */
public record BudgetChangedEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID budgetId,
        Long userId,
        Long categoryId,
        String currency,
        BigDecimal limitAmount,
        String startDate,
        String endDate,
        String periodType,
        boolean deleted
) {
}
