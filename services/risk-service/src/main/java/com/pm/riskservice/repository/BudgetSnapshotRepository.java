package com.pm.riskservice.repository;

import com.pm.riskservice.entity.BudgetSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-model of budget definitions for the BUDGET_RISK insight (Phase E.2).
 */
public interface BudgetSnapshotRepository extends JpaRepository<BudgetSnapshot, UUID> {

    /**
     * Active (non-deleted) budgets matching a transaction's user + category + currency whose
     * window contains {@code date}. Mirrors budget-service's matching rule (inclusive window),
     * so utilization is computed against the same budgets that absorbed the spend.
     */
    @Query("""
            select b from BudgetSnapshot b
            where b.userId = :userId
              and b.categoryId = :categoryId
              and b.currency = :currency
              and b.deleted = false
              and b.startDate <= :date
              and b.endDate >= :date
            """)
    List<BudgetSnapshot> findActiveMatching(@Param("userId") Long userId,
                                            @Param("categoryId") Long categoryId,
                                            @Param("currency") String currency,
                                            @Param("date") LocalDate date);
}
