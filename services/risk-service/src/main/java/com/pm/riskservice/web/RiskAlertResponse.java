package com.pm.riskservice.web;

import com.pm.riskservice.entity.RiskAlert;

import java.time.Instant;
import java.util.UUID;

/**
 * REST view of a {@link RiskAlert}. Keeps the API contract decoupled from the entity.
 */
public record RiskAlertResponse(
        UUID id,
        Long userId,
        UUID transactionId,
        String riskType,
        String riskSeverity,
        Instant occurredAt,
        Instant createdAt
) {

    public static RiskAlertResponse from(RiskAlert alert) {
        return new RiskAlertResponse(
                alert.getId(),
                alert.getUserId(),
                alert.getTransactionId(),
                alert.getRiskType(),
                alert.getRiskSeverity(),
                alert.getOccurredAt(),
                alert.getCreatedAt());
    }
}
