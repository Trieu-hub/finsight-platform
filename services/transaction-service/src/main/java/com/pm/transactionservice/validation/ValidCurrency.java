package com.pm.transactionservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Validates that a string is a valid ISO 4217 currency code (e.g. "USD", "EUR").
 */
@Documented
@Constraint(validatedBy = CurrencyValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ValidCurrency {

    String message() default "currency must be a valid ISO 4217 code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
