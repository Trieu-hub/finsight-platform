package com.pm.budgetservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side copy of transaction-service's {@code TransactionCreated} wire contract
 * (topic {@code finsight.transactions.created}). Deliberately duplicated rather than
 * shared as a library: each service owns its view of the contract and deserializes by
 * the documented schema, exactly as a non-JVM consumer would (the producer omits JSON
 * type headers for the same reason).
 *
 * <p>Differences from the producer's class are intentional leniency: {@code type} is a
 * {@code String} (an unknown future type, e.g. TRANSFER, must be ignored — not become a
 * deserialization failure), and the ISO-8601 date/instant fields stay {@code String}s,
 * mirroring the wire shape.
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
