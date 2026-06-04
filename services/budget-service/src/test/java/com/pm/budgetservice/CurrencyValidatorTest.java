package com.pm.budgetservice;

import com.pm.budgetservice.validation.CurrencyValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyValidatorTest {

    private final CurrencyValidator validator = new CurrencyValidator();

    @Test
    void acceptsValidIso4217Codes() {
        assertTrue(validator.isValid("USD", null));
        assertTrue(validator.isValid("EUR", null));
        assertTrue(validator.isValid("VND", null));
    }

    @Test
    void rejectsUnknownCodes() {
        assertFalse(validator.isValid("US", null));
        assertFalse(validator.isValid("usd", null));
        assertFalse(validator.isValid("XYZ", null));
    }

    @Test
    void treatsNullAsValidSoNotNullOwnsPresence() {
        assertTrue(validator.isValid(null, null));
    }
}
