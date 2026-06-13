package com.pm.riskservice.repository;

import com.pm.riskservice.entity.ExpenseObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persistence + windowed aggregate queries for {@link ExpenseObservation}, backing the
 * RAPID_SPENDING (count over a time window) and LARGE_DAILY_SPEND (daily sum) rules.
 */
public interface ObservedExpenseRepository extends JpaRepository<ExpenseObservation, UUID> {

    /** Count of a user's expenses with occurredAt in [from, to] (both inclusive). */
    long countByUserIdAndOccurredAtBetween(Long userId, Instant from, Instant to);

    /** Total expense amount for a user on a calendar day (0 when none). */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from ExpenseObservation e
            where e.userId = :userId and e.transactionDate = :date
            """)
    BigDecimal sumAmountForDay(@Param("userId") Long userId, @Param("date") LocalDate date);

    /**
     * Total expense amount for a user over a half-open day range [startInclusive,
     * endExclusive) (0 when none). Used by the SPENDING_INCREASE insight to total a calendar
     * month (start = first day of month, end = first day of next month).
     */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from ExpenseObservation e
            where e.userId = :userId
              and e.transactionDate >= :startInclusive
              and e.transactionDate < :endExclusive
            """)
    BigDecimal sumAmountInDateRange(@Param("userId") Long userId,
                                    @Param("startInclusive") LocalDate startInclusive,
                                    @Param("endExclusive") LocalDate endExclusive);

    /**
     * Total expense amount for a user in one category over a half-open day range
     * [startInclusive, endExclusive) (0 when none). Backs CATEGORY_SURGE's monthly totals.
     */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from ExpenseObservation e
            where e.userId = :userId
              and e.categoryId = :categoryId
              and e.transactionDate >= :startInclusive
              and e.transactionDate < :endExclusive
            """)
    BigDecimal sumAmountForCategoryInDateRange(@Param("userId") Long userId,
                                               @Param("categoryId") Long categoryId,
                                               @Param("startInclusive") LocalDate startInclusive,
                                               @Param("endExclusive") LocalDate endExclusive);

    /**
     * Total expense for a user in one category+currency over an inclusive day range
     * [start, end] (0 when none). Backs BUDGET_RISK's utilization — currency-exact and an
     * inclusive window, mirroring budget-service's budget matching.
     */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from ExpenseObservation e
            where e.userId = :userId
              and e.categoryId = :categoryId
              and e.currency = :currency
              and e.transactionDate >= :startInclusive
              and e.transactionDate <= :endInclusive
            """)
    BigDecimal sumAmountForBudgetWindow(@Param("userId") Long userId,
                                        @Param("categoryId") Long categoryId,
                                        @Param("currency") String currency,
                                        @Param("startInclusive") LocalDate startInclusive,
                                        @Param("endInclusive") LocalDate endInclusive);
}
