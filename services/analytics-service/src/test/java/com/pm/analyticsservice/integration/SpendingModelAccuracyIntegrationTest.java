package com.pm.analyticsservice.integration;

import com.pm.analyticsservice.entity.SpendingModel;
import com.pm.analyticsservice.forecast.BacktestResult;
import com.pm.analyticsservice.repository.SpendingModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The accuracy queries behind the gauges, against real MySQL. Mocking the repository would
 * prove the arithmetic in Java and nothing about the JPQL — including whether a `NULL` score
 * or the `model_mae < baseline_mae * 0.95` comparison behave as intended in the database.
 * Booting the context also re-proves that `V4` and the entity mapping still agree.
 */
class SpendingModelAccuracyIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final BigDecimal WIN_FACTOR =
            BigDecimal.valueOf(1.0 - BacktestResult.REQUIRED_IMPROVEMENT);

    @Autowired
    private SpendingModelRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("counts only the fits that beat the run rate by the required margin")
    void countsWinnersLosersAndUnscored() {
        repository.save(scored(1L, "10.000000", "100.000000"));   // a clear win
        repository.save(scored(2L, "96.000000", "100.000000"));   // better, but by 4% — not enough
        repository.save(scored(3L, "150.000000", "100.000000"));  // worse than doing nothing
        repository.save(unscored(4L));                            // too little history to score

        assertThat(repository.count()).isEqualTo(4);
        // The 4% improvement is deliberately on the losing side of the 5% margin: this is the
        // boundary the whole gate turns on, so it is the one worth pinning in the database.
        assertThat(repository.countBeatingRunRate(WIN_FACTOR)).isEqualTo(1);
        assertThat(repository.countByModelMaeIsNull()).isEqualTo(1);
    }

    @Test
    @DisplayName("averages the error ratio over scored fits only")
    void averagesTheErrorRatio() {
        repository.save(scored(1L, "10.000000", "100.000000"));   // 0.10
        repository.save(scored(2L, "96.000000", "100.000000"));   // 0.96
        repository.save(scored(3L, "150.000000", "100.000000"));  // 1.50
        repository.save(unscored(4L));                            // contributes nothing

        assertThat(repository.averageErrorRatio())
                .isCloseTo((0.10 + 0.96 + 1.50) / 3, within(1e-6));
    }

    @Test
    @DisplayName("reports no ratio at all when nothing has been scored")
    void returnsNullWithoutScores() {
        repository.save(unscored(1L));

        // Null, not zero — the gauge turns this into NaN, i.e. a gap on the panel rather than
        // a flattering "the model has no error".
        assertThat(repository.averageErrorRatio()).isNull();
    }

    private SpendingModel scored(long userId, String modelMae, String baselineMae) {
        SpendingModel model = unscored(userId);
        model.setHoldoutDays(14);
        model.setModelMae(new BigDecimal(modelMae));
        model.setBaselineMae(new BigDecimal(baselineMae));
        return model;
    }

    private SpendingModel unscored(long userId) {
        BigDecimal one = BigDecimal.ONE;
        return SpendingModel.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .currency("USD")
                .levelValue(new BigDecimal("50.000000"))
                .trendValue(BigDecimal.ZERO)
                .dowMon(one).dowTue(one).dowWed(one).dowThu(one)
                .dowFri(one).dowSat(one).dowSun(one)
                .sigma(new BigDecimal("5.000000"))
                .sampleDays(120)
                .trainedUpto(LocalDate.now().minusDays(1))
                .trainedAt(LocalDateTime.now())
                .build();
    }
}
