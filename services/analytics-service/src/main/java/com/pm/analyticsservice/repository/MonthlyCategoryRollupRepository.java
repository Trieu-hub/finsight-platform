package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Everyone who had any activity in a month — the recipients of that month's report
     * (Phase G.2). This is the one query in the service that crosses users, and it is a
     * scheduled job rather than a request: no caller has a JWT to be scoped by.
     */
    @Query("""
            select distinct r.userId
            from MonthlyCategoryRollup r
            where r.yearMonth = :yearMonth
            """)
    List<Long> findUserIdsWithActivityIn(@Param("yearMonth") String yearMonth);
}
