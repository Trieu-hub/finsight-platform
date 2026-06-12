package com.pm.transactionservice.event;

import com.pm.transactionservice.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code TransactionCreated} integration event — the wire contract published to
 * Kafka after a transaction is successfully persisted. This is the first real
 * asynchronous contract in FinSight; future Risk / Anomaly / Analytics consumers
 * depend on its shape, so it is deliberately decoupled from the {@code Transaction}
 * JPA entity. Internal persistence changes must not silently alter this payload.
 *
 * <p>Envelope fields ({@code eventId}, {@code eventType}, {@code occurredAt}) wrap the
 * transaction snapshot so consumers can de-duplicate, route, and order events without
 * parsing the body.
 *
 * <p>A {@code record} serializes cleanly via the JSON serializer (public accessors,
 * no Lombok needed).
 */
public record TransactionCreatedEvent(
        UUID eventId,
        String eventType,
        // ISO-8601 strings, not java.time types, so the wire shape is stable and
        // language-neutral regardless of the producer's Jackson date configuration
        // (the default serializer writes java.time as timestamps/arrays).
        String occurredAt,
        UUID transactionId,
        Long userId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Long categoryId,
        String transactionDate,
        Long walletId
) {

    /** Stable discriminator carried in {@link #eventType()}. */
    public static final String EVENT_TYPE = "TransactionCreated";

    /**
     * Builds an event from a persisted transaction's fields, stamping a fresh
     * {@code eventId} and the current {@code occurredAt}.
     */
    public static TransactionCreatedEvent of(UUID transactionId,
                                             Long userId,
                                             TransactionType type,
                                             BigDecimal amount,
                                             String currency,
                                             Long categoryId,
                                             LocalDate transactionDate,
                                             Long walletId) {
        return new TransactionCreatedEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                Instant.now().toString(),
                transactionId,
                userId,
                type,
                amount,
                currency,
                categoryId,
                transactionDate == null ? null : transactionDate.toString(),
                walletId);
    }
}
