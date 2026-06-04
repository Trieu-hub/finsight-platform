package com.pm.authservice.exception;

/**
 * Thrown when login is blocked because the account has exceeded the allowed number of
 * failed attempts. Mapped to 429 ACCOUNT_LOCKED.
 */
public class AccountLockedException extends RuntimeException {

    public AccountLockedException(String message) {
        super(message);
    }
}
