package com.pm.budgetservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side copy of transaction-service's {@code TransactionUpdated} wire contract
 * (topic {@code finsight.transactions.updated}). Deliberately duplicated rather than
 * shared as a library — each service owns its view of the contract, exactly as the
 * {@link TransactionCreatedEvent} copy does.
 *
 * <p>Carries both the pre-edit ("old") and post-edit ("new") snapshot: to keep
 * {@code spent_amount} accurate the consumer reverses the old contribution and applies
 * the new one. {@code oldType}/{@code newType} are {@code String}s for the same
 * leniency reason as the created event (an unknown type must be ignored, not fail
 * deserialization); temporal fields stay ISO-8601 {@code String}s.
 */
public record TransactionUpdatedEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID transactionId,
        Long userId,
        String oldType,
        BigDecimal oldAmount,
        String oldCurrency,
        Long oldCategoryId,
        String oldTransactionDate,
        String newType,
        BigDecimal newAmount,
        String newCurrency,
        Long newCategoryId,
        String newTransactionDate
) {
}
