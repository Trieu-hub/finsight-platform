package com.pm.budgetservice.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single EXPENSE contribution to budget utilization: the tuple the matching rules use
 * ({@code categoryId}, {@code currency}, {@code amount}, {@code date}). Used by
 * {@link BudgetService#applyUpdate} to describe the "reverse the old slot" and "apply the
 * new slot" sides of a transaction edit as two small values instead of a long parameter
 * list. {@code amount} is always the positive spend; reversal negates it internally.
 */
public record ExpenseLine(Long categoryId, String currency, BigDecimal amount, LocalDate date) {
}
