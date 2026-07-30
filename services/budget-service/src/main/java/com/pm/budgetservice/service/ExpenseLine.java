package com.pm.budgetservice.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single EXPENSE contribution to budget utilization: the budget it is charged against and the
 * amount. Used by {@link BudgetService#applyUpdate} to describe the "reverse the old slot" and
 * "apply the new slot" sides of a transaction edit as two small values. {@code amount} is always
 * the positive spend; reversal negates it internally. A null {@code budgetId} matches no budget.
 */
public record ExpenseLine(UUID budgetId, BigDecimal amount) {
}
