package com.pm.riskservice.event;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Lenient parsing of the ISO-8601 string temporal fields carried on the consumed events
 * ({@code occurredAt}, {@code transactionDate}, budget {@code startDate}/{@code endDate}).
 * Returns {@code null} for a null or unparseable value rather than throwing, so a malformed
 * field makes the rule/insight/anomaly skip the event instead of failing the listener.
 *
 * <p>Internal helper extracting the identical private {@code parseInstant}/{@code parseDate}
 * copies that previously lived in the rule engine, the insight/anomaly services, and the
 * budget consumer (DL-1). Not part of any public API.
 */
public final class EventTimes {

    private EventTimes() {
    }

    /** Parses an ISO-8601 instant, or {@code null} when the value is null/unparseable. */
    public static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Parses an ISO-8601 local date, or {@code null} when the value is null/unparseable. */
    public static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
