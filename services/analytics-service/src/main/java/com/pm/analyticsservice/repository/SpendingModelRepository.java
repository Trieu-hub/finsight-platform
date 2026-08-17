package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.entity.SpendingModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface SpendingModelRepository extends JpaRepository<SpendingModel, UUID> {

    Optional<SpendingModel> findByUserIdAndCurrency(Long userId, String currency);

    /** Fits that could not be scored — too little history to withhold a holdout from. */
    long countByModelMaeIsNull();

    /**
     * Fits that earned the right to answer: they beat the run rate by the required margin.
     * The margin arrives as a parameter rather than being written into the query, so
     * {@code BacktestResult.REQUIRED_IMPROVEMENT} stays the single place it is defined — a
     * gauge that counted winners by a different rule than the forecast serves by would be
     * worse than no gauge.
     */
    @Query("""
            SELECT COUNT(m)
              FROM SpendingModel m
             WHERE m.modelMae IS NOT NULL
               AND m.modelMae < m.baselineMae * :factor
            """)
    long countBeatingRunRate(@Param("factor") BigDecimal factor);

    /**
     * Mean of {@code model_mae / baseline_mae} across every scored fit — how much better the
     * models are, not just how many won. Below 1.0 is an improvement; 0.6 means the model's
     * average error is 40% smaller than the run rate's. Null when nothing has been scored yet.
     */
    @Query("""
            SELECT AVG(m.modelMae / m.baselineMae)
              FROM SpendingModel m
             WHERE m.modelMae IS NOT NULL
               AND m.baselineMae > 0
            """)
    Double averageErrorRatio();
}
