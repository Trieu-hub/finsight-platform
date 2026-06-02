package com.pm.transactionservice.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

public class CurrencyValidator implements ConstraintValidator<ValidCurrency, String> {

    private static final Set<String> ISO_4217_CODES =
            Currency.getAvailableCurrencies().stream()
                    .map(Currency::getCurrencyCode)
                    .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null is handled by @NotNull; treat absence as valid here.
        if (value == null) {
            return true;
        }
        return ISO_4217_CODES.contains(value);
    }
}
