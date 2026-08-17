package com.pm.analyticsservice.integration;

import com.pm.analyticsservice.entity.SpendingModel;
import com.pm.analyticsservice.forecast.ForecastModelMetrics;
import com.pm.analyticsservice.repository.SpendingModelRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the gauges exist at runtime — not merely that the class compiles.
 *
 * <p>Everything about this metric is conditional: the bean only exists when the forecast model
 * is enabled, the values are seeded from an {@code ApplicationReadyEvent}, and the meter names
 * are strings. Each of those can fail silently and leave a dashboard that is empty for a reason
 * nobody can see. This boots the real context with the flag on and reads the registry back.
 */
@TestPropertySource(properties = "finsight.forecast.model.enabled=true")
class ForecastModelMetricsIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ForecastModelMetrics metrics;
    @Autowired
    private SpendingModelRepository repository;

    @Test
    @DisplayName("registers one gauge per outcome once the model is enabled")
    void registersTheGauges() {
        assertThat(gauge("serving")).isNotNull();
        assertThat(gauge("beaten")).isNotNull();
        assertThat(gauge("unvalidated")).isNotNull();
        assertThat(meterRegistry.find("finsight.analytics.forecast.model.error.ratio").gauge())
                .isNotNull();
    }

    @Test
    @DisplayName("reads real rows through the real queries")
    void countsWhatIsInTheTable() {
        repository.deleteAll();
        repository.save(model(1L, new BigDecimal("10.000000"), new BigDecimal("100.000000")));
        repository.save(model(2L, new BigDecimal("150.000000"), new BigDecimal("100.000000")));
        repository.save(model(3L, null, null));

        metrics.refresh();

        assertThat(gauge("serving").value()).isEqualTo(1.0);
        assertThat(gauge("beaten").value()).isEqualTo(1.0);
        assertThat(gauge("unvalidated").value()).isEqualTo(1.0);
    }

    private io.micrometer.core.instrument.Gauge gauge(String outcome) {
        return meterRegistry.find("finsight.analytics.forecast.models")
                .tag("outcome", outcome)
                .gauge();
    }

    private SpendingModel model(long userId, BigDecimal modelMae, BigDecimal baselineMae) {
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
                .holdoutDays(modelMae == null ? null : 14)
                .modelMae(modelMae)
                .baselineMae(baselineMae)
                .build();
    }
}
