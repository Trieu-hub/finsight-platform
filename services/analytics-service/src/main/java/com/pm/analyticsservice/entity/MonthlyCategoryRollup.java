package com.pm.analyticsservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Pre-aggregated read model: one row per (user, month, category, type, currency),
 * carrying the running total amount and transaction count. The consumer folds each
 * {@code TransactionCreated} into the matching row; the read APIs answer
 * month-over-month, category-breakdown and forecast queries straight off these rows
 * without ever scanning raw transactions.
 *
 * <p>{@code categoryId == 0} is the sentinel for an uncategorized transaction (the event
 * may carry a null categoryId, which cannot sit in a composite uniqueness rule).
 */
@Entity
@Table(name = "monthly_category_rollup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyCategoryRollup {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Calendar month as {@code "YYYY-MM"} — sorts chronologically as a plain string.
     * Mapped to {@code period_month}: {@code YEAR_MONTH} is a reserved word in MySQL, so the
     * column cannot be named {@code year_month}. The Java property stays {@code yearMonth}.
     */
    @Column(name = "period_month", nullable = false, length = 7)
    private String yearMonth;

    /** 0 == uncategorized. Otherwise the transaction-service category id. */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** INCOME or EXPENSE (the transaction type, by name). */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "txn_count", nullable = false)
    private int txnCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
