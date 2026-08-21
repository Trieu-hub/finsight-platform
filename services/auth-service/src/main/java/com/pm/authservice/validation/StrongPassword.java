package com.pm.authservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A password that is long enough and is not one an attacker would try first.
 *
 * <p>Deliberately <b>no composition rules</b> — no "must contain a digit and a symbol". NIST
 * SP 800-63B dropped them because they push people toward {@code Password1!}, which satisfies
 * every rule and is on every cracking list. Length plus a blocklist is what actually helps, and
 * it is what this checks.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "Password is too common or too easy to guess";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
