package com.pm.budgetservice.exception;

/**
 * Thrown when a budget would duplicate an existing active budget for the same
 * (userId, categoryId, periodType, startDate). Mapped to 409 CONFLICT.
 */
public class BudgetConflictException extends RuntimeException {

    public BudgetConflictException(String message) {
        super(message);
    }
}
