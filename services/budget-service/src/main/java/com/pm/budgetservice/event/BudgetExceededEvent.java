package com.pm.budgetservice.event;

import com.pm.budgetservice.entity.Budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted the moment a budget's spend crosses its limit — once per crossing, not once per
 * expense that lands while it is over.
 *
 * <p>Distinct from {@link BudgetChangedEvent}, which snapshots a budget's <em>configuration</em>
 * when the user creates, edits or deletes it. This one is about the <em>money</em>, and it is the
 * only place in the platform that reports over-budget from the authoritative figure: budget-service
 * owns {@code spent_amount} and is the number the Budgets page renders. risk-service also derives a
 * budget utilization signal, but from its own eventually-consistent read-model — good enough for an
 * advisory insight, not for telling someone they overspent.
 *
 * @param limitAmount the budget's limit at the moment of crossing
 * @param spentAmount total spend after this expense was applied — strictly greater than the limit
 */
public record BudgetExceededEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID budgetId,
        Long userId,
        Long categoryId,
        String currency,
        BigDecimal limitAmount,
        BigDecimal spentAmount
) {

    public static final String EVENT_TYPE = "BudgetExceeded";

    public static BudgetExceededEvent of(Budget budget, BigDecimal spentAmount) {
        return new BudgetExceededEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                Instant.now().toString(),
                budget.getId(),
                budget.getUserId(),
                budget.getCategoryId(),
                budget.getCurrency(),
                budget.getLimitAmount(),
                spentAmount);
    }
}
