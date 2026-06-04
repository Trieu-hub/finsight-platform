package com.pm.budgetservice.exception;

/** Thrown for service-level validation failures (e.g. bad amount or date range). Mapped to 400. */
public class InvalidBudgetDataException extends RuntimeException {

    public InvalidBudgetDataException(String message) {
        super(message);
    }
}
