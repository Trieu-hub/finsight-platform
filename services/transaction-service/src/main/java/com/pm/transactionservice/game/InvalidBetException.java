package com.pm.transactionservice.game;

/** The chips submitted are not a position that exists on the table, or exceed the stake limit. */
public class InvalidBetException extends RuntimeException {

    public InvalidBetException(String message) {
        super(message);
    }
}
