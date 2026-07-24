package com.pm.transactionservice.event;

import com.pm.transactionservice.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code TransactionUpdated} integration event — published to Kafka after a
 * transaction is edited. Unlike {@link TransactionCreatedEvent} it carries <em>both</em>
 * the pre-edit ("old") and post-edit ("new") snapshot, because a consumer that
 * materializes a running total (budget-service's {@code spent_amount}) must first
 * reverse the old contribution and then apply the new one to stay accurate. Without the
 * old snapshot a consumer could not know what to subtract.
 *
 * <p>Same envelope + wire conventions as {@link TransactionCreatedEvent}: ISO-8601
 * strings for temporal fields (language-neutral), no JSON type headers, a fresh
 * {@code eventId} per emission so consumers can de-duplicate independently of the
 * create/delete events for the same transaction.
 */
public record TransactionUpdatedEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID transactionId,
        Long userId,
        // Pre-edit snapshot — what a consumer must reverse.
        TransactionType oldType,
        BigDecimal oldAmount,
        String oldCurrency,
        Long oldCategoryId,
        String oldTransactionDate,
        // The budget the old slot was charged against (reverse from this exact budget).
        UUID oldBudgetId,
        // Post-edit snapshot — what a consumer must apply.
        TransactionType newType,
        BigDecimal newAmount,
        String newCurrency,
        Long newCategoryId,
        String newTransactionDate,
        // The budget the new slot is charged against (apply to this exact budget).
        UUID newBudgetId
) {

    /** Stable discriminator carried in {@link #eventType()}. */
    public static final String EVENT_TYPE = "TransactionUpdated";

    /** Builds an event from the pre-/post-edit fields, stamping a fresh {@code eventId}. */
    public static TransactionUpdatedEvent of(UUID transactionId,
                                             Long userId,
                                             TransactionType oldType,
                                             BigDecimal oldAmount,
                                             String oldCurrency,
                                             Long oldCategoryId,
                                             LocalDate oldTransactionDate,
                                             UUID oldBudgetId,
                                             TransactionType newType,
                                             BigDecimal newAmount,
                                             String newCurrency,
                                             Long newCategoryId,
                                             LocalDate newTransactionDate,
                                             UUID newBudgetId) {
        return new TransactionUpdatedEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                Instant.now().toString(),
                transactionId,
                userId,
                oldType,
                oldAmount,
                oldCurrency,
                oldCategoryId,
                oldTransactionDate == null ? null : oldTransactionDate.toString(),
                oldBudgetId,
                newType,
                newAmount,
                newCurrency,
                newCategoryId,
                newTransactionDate == null ? null : newTransactionDate.toString(),
                newBudgetId);
    }
}
