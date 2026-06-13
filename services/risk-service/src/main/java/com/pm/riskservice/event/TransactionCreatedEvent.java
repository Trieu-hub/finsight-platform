package com.pm.riskservice.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Consumer-side copy of transaction-service's {@code TransactionCreated} wire contract
 * (topic {@code finsight.transactions.created}). Deliberately duplicated rather than
 * shared as a library: each service owns its view of the contract and deserializes by
 * the documented schema, exactly as a non-JVM consumer would (the producer omits JSON
 * type headers for the same reason).
 *
 * <p>{@code type} is a {@code String} (not an enum) for forward-compatibility: an
 * unknown future transaction type must be ignored by the rule, not become a
 * deserialization failure. Temporal fields stay {@code String}s, mirroring the wire shape.
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
