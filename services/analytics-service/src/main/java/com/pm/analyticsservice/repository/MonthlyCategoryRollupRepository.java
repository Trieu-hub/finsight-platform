package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MonthlyCategoryRollupRepository extends JpaRepository<MonthlyCategoryRollup, UUID> {

    /** The single row a TransactionCreated event folds into (the upsert target). */
    Optional<MonthlyCategoryRollup> findByUserIdAndYearMonthAndCategoryIdAndTypeAndCurrency(
            Long userId, String yearMonth, Long categoryId, String type, String currency);

    /** All of one user's rows for one month (overview / forecast / summary). */
    List<MonthlyCategoryRollup> findByUserIdAndYearMonth(Long userId, String yearMonth);

    /**
     * All of one user's rows across an inclusive month range. Because {@code year_month}
     * is {@code "YYYY-MM"}, lexical BETWEEN is also chronological BETWEEN.
     */
    List<MonthlyCategoryRollup> findByUserIdAndYearMonthBetween(
            Long userId, String fromYearMonth, String toYearMonth);
}
