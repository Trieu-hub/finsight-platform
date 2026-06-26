package com.pm.notificationservice.event;

import java.util.UUID;

/**
 * Consumer-side copy of risk-service's {@code RiskDetected} wire contract (published to
 * {@code finsight.risk.detected}). This is a deliberate duplicate of the producer's
 * record — the two services share no code, only the documented JSON shape (see
 * {@code docs/event-catalog.md}). {@code riskType}/{@code riskSeverity} are plain
 * Strings so a new rule output never breaks deserialization here.
 *
 * <p>{@code occurredAt} is an ISO-8601 string on the wire (language-neutral); this
 * service does not need to parse it, so it is kept as a String.
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
}
