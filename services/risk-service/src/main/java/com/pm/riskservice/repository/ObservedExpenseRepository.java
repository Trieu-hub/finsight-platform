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
 * RAPID_SPENDING (count over a time window) and LARGE_DAILY_SPEND (daily sum) rules, the
 * behavioral insights, and the UNUSUAL_TRANSACTION_AMOUNT anomaly.
 *
 * <p>Since Phase E.3 the table also holds INCOME rows, so every expense aggregate filters
 * {@code transactionType = 'EXPENSE'} to keep its exact prior behaviour; income is summed
 * separately for LOW_SAVINGS_RATE.
 */
public interface ObservedExpenseRepository extends JpaRepository<ExpenseObservation, UUID> {

    /** Count of a user's EXPENSE observations with occurredAt in [from, to] (both inclusive). */
    @Query("""
            select count(e)
            from ExpenseObservation e
            where e.userId = :userId
              and e.transactionType = 'EXPENSE'
              and e.occurredAt between :from and :to
            """)
    long countByUserIdAndOccurredAtBetween(@Param("userId") Long userId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to);

    /** Total expense amount for a user on a calendar day (0 when none). */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from ExpenseObservation e
            where e.userId = :userId
              and e.transactionType = 'EXPENSE'
              and e.transactionDate = :date
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
              and e.transactionType = 'EXPENSE'
              and e.transactionDate >= :startInclusive
              and e.transactionDate < :endExclusive
            """)
    BigDecimal sumAmountInDateRange(@Param("userId") Long userId,
                                    @Param("startInclusive") LocalDate startInclusive,
                                    @Param("endExclusive") LocalDate endExclusive);

    /**
     * Total INCOME amount for a user over a half-open day range [startInclusive, endExclusive)
     * (0 when none). Backs the LOW_SAVINGS_RATE income side (Phase E.3).
     */
    @Query("""
            select coalesce(sum(e.amount), 0)
            from ExpenseObservation e
            where e.userId = :userId
              and e.transactionType = 'INCOME'
              and e.transactionDate >= :startInclusive
              and e.transactionDate < :endExclusive
            """)
    BigDecimal sumIncomeInDateRange(@Param("userId") Long userId,
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
              and e.transactionType = 'EXPENSE'
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
              and e.transactionType = 'EXPENSE'
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

    /**
     * The UNUSUAL_TRANSACTION_AMOUNT baseline (Phase F.1) in a single pass: the count and average
     * amount of a user's EXPENSE observations strictly before {@code before} (excluding the
     * just-recorded triggering expense, whose occurredAt equals {@code before}). {@code average}
     * is {@code null} when there are no prior expenses.
     *
     * <p>PF-2: one query instead of two over the same filtered set — the range scan runs once.
     * Backed by the {@code idx_observed_user_type_occurred} index (user_id, transaction_type,
     * occurred_at) that exactly matches this predicate.
     */
    @Query("""
            select count(e) as count, avg(e.amount) as average
            from ExpenseObservation e
            where e.userId = :userId
              and e.transactionType = 'EXPENSE'
              and e.occurredAt < :before
            """)
    ExpenseBaseline expenseBaselineBefore(@Param("userId") Long userId,
                                          @Param("before") Instant before);

    /** Projection for {@link #expenseBaselineBefore} — prior-expense count and average amount. */
    interface ExpenseBaseline {
        long getCount();

        BigDecimal getAverage();
    }
}
