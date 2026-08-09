package com.pm.riskservice.web;

import com.pm.riskservice.entity.RecurringSeries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * REST view of a {@link RecurringSeries}. Keeps the API contract decoupled from the entity.
 */
public record RecurringSeriesResponse(
        UUID id,
        Long userId,
        Long categoryId,
        String currency,
        BigDecimal typicalAmount,
        int intervalDays,
        int occurrences,
        LocalDate firstSeen,
        LocalDate lastSeen,
        LocalDate nextExpected,
        String status
) {

    public static RecurringSeriesResponse from(RecurringSeries series) {
        return new RecurringSeriesResponse(
                series.getId(),
                series.getUserId(),
                series.getCategoryId(),
                series.getCurrency(),
                series.getTypicalAmount(),
                series.getIntervalDays(),
                series.getOccurrences(),
                series.getFirstSeen(),
                series.getLastSeen(),
                series.getNextExpected(),
                series.getStatus());
    }
}
