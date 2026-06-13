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
 * A generated behavioral insight (Phase E.1). The first and only type is SPENDING_INCREASE:
 * a user's current-month EXPENSE total exceeded the previous month's by at least 30%. Derived
 * from {@code observed_expenses}; the amounts and percentage are snapshotted at generation
 * time. Plain JPA (no Lombok) to keep this lean module dependency-free.
 */
@Entity
@Table(name = "insights")
public class Insight {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "insight_type", nullable = false, length = 50)
    private String insightType;

    /** The flagged (current) month, 'YYYY-MM'. For BUDGET_RISK, the budget's start month. */
    @Column(name = "period_month", nullable = false, length = 7)
    private String periodMonth;

    /** The category this insight concerns (CATEGORY_SURGE, BUDGET_RISK); null otherwise. */
    @Column(name = "category_id")
    private Long categoryId;

    /**
     * Per-type dedup discriminator (see V5): '-' for SPENDING_INCREASE, the category id for
     * CATEGORY_SURGE, the budget id for BUDGET_RISK.
     */
    @Column(name = "subject_id", nullable = false, length = 64)
    private String subjectId;

    /** Previous-period figure: prior-month total, or — for BUDGET_RISK — the budget limit. */
    @Column(name = "previous_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal previousAmount;

    /** Current-period figure: current-month total, or — for BUDGET_RISK — the spent amount. */
    @Column(name = "current_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentAmount;

    /**
     * Percent figure: the month-over-month increase (SPENDING_INCREASE, CATEGORY_SURGE) or the
     * budget utilization (BUDGET_RISK), e.g. 45.00 for +45% / 85.00 for 85% used.
     */
    @Column(name = "increase_pct", nullable = false, precision = 9, scale = 2)
    private BigDecimal increasePct;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    protected Insight() {
        // JPA
    }

    public Insight(UUID id, Long userId, String insightType, String periodMonth, Long categoryId,
                   String subjectId, BigDecimal previousAmount, BigDecimal currentAmount,
                   BigDecimal increasePct, Instant generatedAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.insightType = insightType;
        this.periodMonth = periodMonth;
        this.categoryId = categoryId;
        this.subjectId = subjectId;
        this.previousAmount = previousAmount;
        this.currentAmount = currentAmount;
        this.increasePct = increasePct;
        this.generatedAt = generatedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getInsightType() {
        return insightType;
    }

    public String getPeriodMonth() {
        return periodMonth;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public BigDecimal getPreviousAmount() {
        return previousAmount;
    }

    public BigDecimal getCurrentAmount() {
        return currentAmount;
    }

    public BigDecimal getIncreasePct() {
        return increasePct;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
