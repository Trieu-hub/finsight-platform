package com.pm.budgetservice.event;

import com.pm.budgetservice.entity.Budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Wire contract for a budget lifecycle change, published to {@code finsight.budgets.changed}
 * after a create, update or (soft) delete commits. budget-service owns this topic; risk-service
 * consumes it to maintain a local budget read-model for the BUDGET_RISK insight (Phase E.2).
 *
 * <p>Mirrors the {@code TransactionCreated} conventions: JSON without type headers (language
 * neutral), temporal fields as ISO strings, and {@code periodType} as a plain string so an
 * unknown future value never breaks a consumer's deserialization. {@code deleted=true} means
 * the budget was soft-deleted and consumers should stop matching it.
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

    public static final String EVENT_TYPE = "BudgetChanged";

    /** Snapshots the current state of {@code budget}; {@code deleted} flags a soft delete. */
    public static BudgetChangedEvent of(Budget budget, boolean deleted) {
        return new BudgetChangedEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                Instant.now().toString(),
                budget.getId(),
                budget.getUserId(),
                budget.getCategoryId(),
                budget.getCurrency(),
                budget.getLimitAmount(),
                budget.getStartDate().toString(),
                budget.getEndDate().toString(),
                budget.getPeriodType().name(),
                deleted);
    }
}
