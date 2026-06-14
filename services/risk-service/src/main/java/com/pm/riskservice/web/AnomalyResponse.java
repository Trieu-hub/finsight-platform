package com.pm.riskservice.web;

import com.pm.riskservice.entity.Anomaly;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * REST view of an {@link Anomaly}. Keeps the API contract decoupled from the entity.
 */
public record AnomalyResponse(
        UUID id,
        Long userId,
        UUID transactionId,
        String anomalyType,
        BigDecimal amount,
        BigDecimal averageAmount,
        BigDecimal ratio,
        Instant occurredAt
) {

    public static AnomalyResponse from(Anomaly anomaly) {
        return new AnomalyResponse(
                anomaly.getId(),
                anomaly.getUserId(),
                anomaly.getTransactionId(),
                anomaly.getAnomalyType(),
                anomaly.getAmount(),
                anomaly.getAverageAmount(),
                anomaly.getRatio(),
                anomaly.getOccurredAt());
    }
}
