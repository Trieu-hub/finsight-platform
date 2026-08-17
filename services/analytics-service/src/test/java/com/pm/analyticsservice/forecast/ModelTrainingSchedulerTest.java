package com.pm.analyticsservice.forecast;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelTrainingSchedulerTest {

    private SpendingModelTrainer trainer;
    private ForecastModelMetrics metrics;
    private MeterRegistry registry;
    private ModelTrainingScheduler scheduler;

    @BeforeEach
    void setUp() {
        trainer = mock(SpendingModelTrainer.class);
        metrics = mock(ForecastModelMetrics.class);
        registry = new SimpleMeterRegistry();
        scheduler = new ModelTrainingScheduler(trainer, metrics, registry);
    }

    @Test
    @DisplayName("trains up to yesterday and re-reads the gauges afterwards")
    void trainsAndRefreshes() {
        when(trainer.trainAll(any())).thenReturn(7);

        scheduler.train();

        // Never up to today: a partial day's spend looks like a collapse to the fit.
        verify(trainer).trainAll(LocalDate.now().minusDays(1));
        verify(metrics).refresh();
        assertThat(counter("finsight.analytics.forecast.models.trained")).isEqualTo(7.0);
        assertThat(counter("finsight.analytics.forecast.training.failed")).isZero();
    }

    @Test
    @DisplayName("a failed sweep is counted, not thrown — and leaves the gauges alone")
    void survivesAFailedSweep() {
        when(trainer.trainAll(any())).thenThrow(new IllegalStateException("db down"));

        // Throwing here would kill the scheduler thread and there would be no further retry.
        assertThatCode(() -> scheduler.train()).doesNotThrowAnyException();

        assertThat(counter("finsight.analytics.forecast.training.failed")).isEqualTo(1.0);
        // Nothing was written, so re-reading would only replace good numbers with the same ones
        // — or with zeros, if the database is what failed.
        verify(metrics, never()).refresh();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }
}
