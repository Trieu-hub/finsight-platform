package com.pm.budgetservice.repository;

import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.enums.BudgetPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
