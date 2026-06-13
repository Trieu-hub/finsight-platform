package com.pm.riskservice.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code RiskDetected} integration event — published to {@code finsight.risk.detected}
 * when a risk rule fires. First risk contract in FinSight; kept intentionally minimal
 * (Phase D.1). Like {@code TransactionCreated}, it carries an envelope
 * ({@code eventId}/{@code eventType}/{@code occurredAt}) plus the detection payload, and
 * uses an ISO-8601 string for the timestamp so the wire shape is language-neutral and
 * independent of the producer's Jackson date configuration.
 *
 * <p>{@code riskType} and {@code riskSeverity} are plain Strings rather than enums so the
 * contract can grow new rule outputs without a breaking change for consumers.
 */
public record RiskDetectedEvent(
        UUID eventId,
        String eventType,
        String occurredAt,
        Long userId,
        UUID transactionId,
        String riskType,
        String riskSeverity
) {

    /** Stable discriminator carried in {@link #eventType()}. */
    public static final String EVENT_TYPE = "RiskDetected";

    /**
     * Builds an event for a detected risk, stamping a fresh {@code eventId} and the
     * current {@code occurredAt}.
     */
    public static RiskDetectedEvent of(Long userId, UUID transactionId,
                                       String riskType, String riskSeverity) {
        return new RiskDetectedEvent(
                UUID.randomUUID(),
                EVENT_TYPE,
                Instant.now().toString(),
                userId,
                transactionId,
                riskType,
                riskSeverity);
    }
}
