package com.pm.analyticsservice.forecast;

import com.pm.analyticsservice.repository.SpendingModelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastModelMetricsTest {

    private static final String MODELS = "finsight.analytics.forecast.models";
    private static final String ERROR_RATIO = "finsight.analytics.forecast.model.error.ratio";

    private SpendingModelRepository repository;
    private MeterRegistry registry;
    private ForecastModelMetrics metrics;

    @BeforeEach
    void setUp() {
        repository = mock(SpendingModelRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new ForecastModelMetrics(repository, registry);
    }

    @Test
    @DisplayName("splits the fitted models into served, beaten and unscored")
    void reportsEachOutcome() {
        givenCounts(10L, 3L, 2L, 0.62);

        metrics.refresh();

        assertThat(gauge("serving")).isEqualTo(3.0);
        assertThat(gauge("unvalidated")).isEqualTo(2.0);
        // Derived, so the three always add up to the ten models that exist.
        assertThat(gauge("beaten")).isEqualTo(5.0);
        assertThat(registry.get(ERROR_RATIO).gauge().value()).isEqualTo(0.62);
    }

    @Test
    @DisplayName("counts winners by the same margin the forecast serves by")
    void usesTheServingThreshold() {
        givenCounts(1L, 1L, 0L, 0.5);

        metrics.refresh();

        ArgumentCaptor<BigDecimal> factor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository).countBeatingRunRate(factor.capture());
        // A gauge counting winners by a looser rule than the one that decides what is served
        // would be worse than no gauge: it would report models that never answer a request.
        assertThat(factor.getValue().doubleValue())
                .isEqualTo(1.0 - BacktestResult.REQUIRED_IMPROVEMENT);
    }

    @Test
    @DisplayName("reports no error ratio at all until something has been scored")
    void reportsNaNBeforeAnythingIsScored() {
        givenCounts(4L, 0L, 4L, null);

        metrics.refresh();

        // NaN rather than 0: zero would plot as "the model is infinitely better than the run
        // rate", which is the opposite of what an unscored platform means.
        assertThat(registry.get(ERROR_RATIO).gauge().value()).isNaN();
        assertThat(gauge("unvalidated")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("keeps the last values when the database is unreachable")
    void survivesARepositoryFailure() {
        givenCounts(10L, 3L, 2L, 0.62);
        metrics.refresh();

        when(repository.count()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> metrics.refresh()).doesNotThrowAnyException();
        // Stale is better than both zeroed (a false "every model lost") and than a training
        // sweep that dies because its metrics could not be read.
        assertThat(gauge("serving")).isEqualTo(3.0);
    }

    private void givenCounts(long total, long winners, long unscored, Double errorRatio) {
        when(repository.count()).thenReturn(total);
        when(repository.countBeatingRunRate(any())).thenReturn(winners);
        when(repository.countByModelMaeIsNull()).thenReturn(unscored);
        when(repository.averageErrorRatio()).thenReturn(errorRatio);
    }

    private double gauge(String outcome) {
        return registry.get(MODELS).tag("outcome", outcome).gauge().value();
    }
}
