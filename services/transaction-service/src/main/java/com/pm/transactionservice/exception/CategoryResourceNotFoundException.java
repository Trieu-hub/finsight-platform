package com.pm.transactionservice.exception;

/**
 * Thrown when a category addressed by URL (GET/PUT/DELETE /categories/{id}) does not
 * exist. Mapped to 404. Distinct from {@link CategoryNotFoundException}, which signals
 * an invalid categoryId in a transaction request body (400).
 */
public class CategoryResourceNotFoundException extends RuntimeException {

    public CategoryResourceNotFoundException(String message) {
        super(message);
    }
}
