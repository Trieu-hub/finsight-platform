package com.pm.riskservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A detected anomaly (Phase F.1). The first type is UNUSUAL_TRANSACTION_AMOUNT: an EXPENSE
 * whose amount is at least 3× the user's average historical expense. Derived from
 * {@code observed_expenses}; {@code amount}, {@code averageAmount} and {@code ratio} are
 * snapshotted at detection time. The {@code id} is the source {@code TransactionCreated} event
 * id, which makes detection idempotent (a redelivered event is not flagged twice). Plain JPA,
 * no Lombok.
 */
@Entity
@Table(name = "anomalies")
public class Anomaly {

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

    @Column(name = "anomaly_type", nullable = false, length = 50)
    private String anomalyType;

    /** The flagged transaction amount. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** The user's average historical expense amount at detection time. */
    @Column(name = "average_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal averageAmount;

    /** {@code amount / averageAmount}, e.g. 3.50 for "3.5× the average". */
    @Column(name = "ratio", nullable = false, precision = 9, scale = 2)
    private BigDecimal ratio;

    /** When the anomaly was detected (the event's occurredAt). */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** When this row was persisted. */
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    protected Anomaly() {
        // JPA
    }

    public Anomaly(UUID id, Long userId, UUID transactionId, String anomalyType, BigDecimal amount,
                   BigDecimal averageAmount, BigDecimal ratio, Instant occurredAt,
                   Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.transactionId = transactionId;
        this.anomalyType = anomalyType;
        this.amount = amount;
        this.averageAmount = averageAmount;
        this.ratio = ratio;
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

    public String getAnomalyType() {
        return anomalyType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getAverageAmount() {
        return averageAmount;
    }

    public BigDecimal getRatio() {
        return ratio;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
