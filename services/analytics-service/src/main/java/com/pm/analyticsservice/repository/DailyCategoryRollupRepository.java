package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.entity.DailyCategoryRollup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyCategoryRollupRepository extends JpaRepository<DailyCategoryRollup, UUID> {

    /** The upsert target for one folded event. */
    Optional<DailyCategoryRollup> findByUserIdAndSpendDateAndCategoryIdAndTypeAndCurrency(
            Long userId, LocalDate spendDate, Long categoryId, String type, String currency);

    /**
     * One user's daily spend over a window, ordered so the trainer can walk it as a series.
     * Rows are per category, so several may share a date — the caller sums them per day.
     */
    List<DailyCategoryRollup> findByUserIdAndTypeAndCurrencyAndSpendDateBetweenOrderBySpendDateAsc(
            Long userId, String type, String currency, LocalDate from, LocalDate to);

    /**
     * Every (user, currency) that has spend in the window — the trainer's work list. Returned
     * as {@code Object[]{userId, currency}} because there is no entity for the pair.
     */
    @Query("""
            SELECT DISTINCT d.userId, d.currency
              FROM DailyCategoryRollup d
             WHERE d.type = :type
               AND d.spendDate BETWEEN :from AND :to
            """)
    List<Object[]> findTrainableSeries(@Param("type") String type,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);
}
