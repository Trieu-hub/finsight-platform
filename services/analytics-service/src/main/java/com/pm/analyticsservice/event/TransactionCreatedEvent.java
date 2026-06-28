package com.pm.analyticsservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side copy of transaction-service's {@code TransactionCreated} wire contract
 * (published on {@code finsight.transactions.created}). It is a deliberate duplicate —
 * each consumer owns its own copy of the schema and we do NOT share a module across
 * services. {@code type} is kept as a {@code String} (the producer serializes the enum
 * by name) so this record carries no dependency on the producer's enum.
 *
 * <p>Envelope fields ({@code eventId}, {@code eventType}, {@code occurredAt}) wrap the
 * transaction snapshot so consumers can de-duplicate and order events.
 */
public record TransactionCreatedEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID transactionId,
        Long userId,
        String type,
        BigDecimal amount,
        String currency,
        Long categoryId,
        String transactionDate,
        Long walletId
) {
}
