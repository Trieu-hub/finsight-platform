package com.pm.riskservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable record of a detected risk (Phase D.2). The {@code id} is the originating
 * {@code RiskDetected} event id, so the persisted row and the published event share an
 * identity. Plain JPA (no Lombok) to keep this lean module dependency-free.
 */
@Entity
@Table(name = "risk_alerts")
public class RiskAlert {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Opaque reference to a transaction-service transaction; not validated cross-service. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", nullable = false, length = 36)
    private UUID transactionId;

    @Column(name = "risk_type", nullable = false, length = 50)
    private String riskType;

    @Column(name = "risk_severity", nullable = false, length = 20)
    private String riskSeverity;

    /** When the risk was detected (the event's occurredAt). */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** When this row was persisted. */
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    protected RiskAlert() {
        // JPA
    }

    public RiskAlert(UUID id, Long userId, UUID transactionId, String riskType,
                     String riskSeverity, Instant occurredAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.transactionId = transactionId;
        this.riskType = riskType;
        this.riskSeverity = riskSeverity;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getRiskType() {
        return riskType;
    }

    public String getRiskSeverity() {
        return riskSeverity;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
