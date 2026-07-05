package com.pm.budgetservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side copy of transaction-service's {@code TransactionDeleted} wire contract
 * (topic {@code finsight.transactions.deleted}). Deliberately duplicated rather than
 * shared as a library, matching the {@link TransactionCreatedEvent} copy.
 *
 * <p>Carries the deleted transaction's snapshot so the consumer can reverse its
 * contribution to {@code spent_amount}. {@code type} is a {@code String} for leniency
 * (an unknown type is ignored, not a deserialization failure); temporal fields stay
 * ISO-8601 {@code String}s.
 */
public record TransactionDeletedEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID transactionId,
        Long userId,
        String type,
        BigDecimal amount,
        String currency,
        Long categoryId,
        String transactionDate
) {
}
