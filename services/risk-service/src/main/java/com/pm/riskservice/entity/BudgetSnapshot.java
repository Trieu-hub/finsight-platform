package com.pm.riskservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Local read-model of a budget definition (Phase E.2), maintained from budget-service's
 * {@code BudgetChanged} events. risk-service never calls budget-service; it keeps just enough
 * state to match a transaction to a budget and compute utilization from observed_expenses.
 * {@code id} is the source budget id, so each event upserts its row. Plain JPA (no Lombok).
 */
@Entity
@Table(name = "budget_snapshots")
public class BudgetSnapshot {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "limit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal limitAmount;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Mirrors budget-service's soft delete; matching excludes deleted budgets. */
    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BudgetSnapshot() {
        // JPA
    }

    public BudgetSnapshot(UUID id, Long userId, Long categoryId, String currency,
                          BigDecimal limitAmount, LocalDate startDate, LocalDate endDate,
                          boolean deleted, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.categoryId = categoryId;
        this.currency = currency;
        this.limitAmount = limitAmount;
        this.startDate = startDate;
        this.endDate = endDate;
        this.deleted = deleted;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getLimitAmount() {
        return limitAmount;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
