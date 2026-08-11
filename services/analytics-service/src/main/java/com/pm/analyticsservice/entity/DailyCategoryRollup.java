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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The same fold as {@link MonthlyCategoryRollup}, one calendar day at a time.
 *
 * <p>It exists because a weekly spending pattern cannot be recovered from monthly totals at
 * any price — the month has already averaged the Saturdays away. This is the series the
 * forecast model trains on.
 *
 * <p>{@code categoryId == 0} is the uncategorized sentinel, as in the monthly rollup.
 */
@Entity
@Table(name = "daily_category_rollup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyCategoryRollup {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The business date the transaction belongs to, not the day the event arrived. */
    @Column(name = "spend_date", nullable = false)
    private LocalDate spendDate;

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
