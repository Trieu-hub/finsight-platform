package com.pm.budgetservice.exception;

/** Thrown when a budget owned by the user cannot be found. Mapped to 404. */
public class BudgetNotFoundException extends RuntimeException {

    public BudgetNotFoundException(String message) {
        super(message);
    }
}
