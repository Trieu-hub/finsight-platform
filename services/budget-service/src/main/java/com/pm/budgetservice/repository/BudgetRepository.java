package com.pm.budgetservice.repository;

import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.enums.BudgetPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * DB access only. Filtering/pagination is expressed through
 * {@link JpaSpecificationExecutor} + {@link BudgetSpecifications}.
 */
public interface BudgetRepository
        extends JpaRepository<Budget, UUID>, JpaSpecificationExecutor<Budget> {

    /** Fetch a single non-deleted budget owned by the given user. */
    Optional<Budget> findByIdAndUserIdAndIsDeletedFalse(UUID id, Long userId);

    /**
     * True if an active (non-deleted) budget already exists for the same period slot.
     * Backs the service-level duplicate guard (a hard UNIQUE constraint would block
     * re-creating a budget after it has been soft-deleted).
     */
    boolean existsByUserIdAndCategoryIdAndPeriodTypeAndStartDateAndIsDeletedFalse(
            Long userId, Long categoryId, BudgetPeriod periodType, LocalDate startDate);

    /**
     * Atomically adds an expense to the single budget the user chose (its id), scoped to the
     * owning user so a spoofed budgetId belonging to someone else can never be charged. One SQL
     * increment — never read-modify-write — so concurrent events cannot lose updates. A null or
     * unknown {@code budgetId} matches no row, leaving spent_amount untouched (a normal outcome
     * for budget-less expenses such as the game).
     *
     * @return number of budgets updated (0 when the budgetId matches no active owned budget)
     *
     * <p>{@code flushAutomatically} is load-bearing: the caller persists the
     * processed_events inbox row in the same transaction, and Hibernate's auto-flush
     * before a bulk query only covers tables the query touches — without the explicit
     * flush, {@code clearAutomatically} would silently discard the pending inbox
     * insert and break idempotency.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Budget b
               SET b.spentAmount = b.spentAmount + :amount
             WHERE b.id = :budgetId
               AND b.userId = :userId
               AND b.isDeleted = false
            """)
    int applyExpense(@Param("userId") Long userId,
                     @Param("budgetId") UUID budgetId,
                     @Param("amount") BigDecimal amount);
}
