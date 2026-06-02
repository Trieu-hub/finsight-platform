package com.pm.transactionservice.exception;

import lombok.Getter;

/**
 * Thrown when a category cannot be deleted because it is a protected system category
 * or is still referenced by transactions. Mapped to 409 CONFLICT. The {@code code}
 * distinguishes the cause in the error envelope (CATEGORY_PROTECTED / CATEGORY_IN_USE).
 */
@Getter
public class CategoryConflictException extends RuntimeException {

    private final String code;

    public CategoryConflictException(String code, String message) {
        super(message);
        this.code = code;
    }
}
