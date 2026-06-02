package com.pm.transactionservice.exception;

/**
 * Thrown by the service layer when transaction data violates an invariant
 * (e.g. amount must be greater than 0) independently of HTTP/bean validation.
 * Mapped to a 400 with the same VALIDATION_ERROR envelope as bean-validation failures.
 */
public class InvalidTransactionDataException extends RuntimeException {

    public InvalidTransactionDataException(String message) {
        super(message);
    }
}
