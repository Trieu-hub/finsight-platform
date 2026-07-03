package com.pm.transactionservice.event;

import com.pm.transactionservice.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code TransactionDeleted} integration event — published to Kafka after a
 * transaction is soft-deleted. It carries the deleted transaction's snapshot so a
 * consumer that materializes a running total (budget-service's {@code spent_amount})
 * can reverse this transaction's contribution.
 *
 * <p>Same envelope + wire conventions as {@link TransactionCreatedEvent}: ISO-8601
 * strings for temporal fields, no JSON type headers, a fresh {@code eventId} per
 * emission for independent de-duplication.
 */
public record TransactionDeletedEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID transactionId,
        Long userId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Long categoryId,
        String transactionDate
) {

    /** Stable discriminator carried in {@link #eventType()}. */
    public static final String EVENT_TYPE = "TransactionDeleted";

    /** Builds an event from the deleted transaction's fields, stamping a fresh {@code eventId}. */
    public static TransactionDeletedEvent of(UUID transactionId,
                                             Long userId,
                                             TransactionType type,
                                             BigDecimal amount,
                                             String currency,
                                             Long categoryId,
                                             LocalDate transactionDate) {
        return new TransactionDeletedEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                Instant.now().toString(),
                transactionId,
                userId,
                type,
                amount,
                currency,
                categoryId,
                transactionDate == null ? null : transactionDate.toString());
    }
}
