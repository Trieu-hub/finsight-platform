package com.pm.authservice.exception;

/**
 * Thrown when a user authenticates with valid credentials but the account is
 * disabled. Mapped to 403 ACCOUNT_DISABLED — distinct from invalid credentials so
 * a correct-password-but-disabled login is not silently treated as a wrong password.
 */
public class DisabledAccountException extends RuntimeException {

    public DisabledAccountException(String message) {
        super(message);
    }
}
