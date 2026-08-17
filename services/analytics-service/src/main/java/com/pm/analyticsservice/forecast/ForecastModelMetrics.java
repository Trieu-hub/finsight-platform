package com.pm.analyticsservice.forecast;

import com.pm.analyticsservice.repository.SpendingModelRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes what the fitted models are actually worth, so the question "does the model beat the
 * run rate in production?" has an answer that is not a grep over last night's logs.
 *
 * <p>Three gauges under one name, tagged by outcome, which therefore sum to every fit that
 * exists:
 * <ul>
 *   <li>{@code serving} — scored, and beat the run rate by the required margin. Only these
 *       answer a request.</li>
 *   <li>{@code beaten} — scored, and did not. The model exists and is deliberately unused.</li>
 *   <li>{@code unvalidated} — not scored at all: too little history to withhold a holdout
 *       from. Also unused, but for a different reason, and the two must not be conflated —
 *       "young" and "no better than the average" call for different responses.</li>
 * </ul>
 * Plus {@code finsight.analytics.forecast.model.error.ratio}: the mean of
 * {@code model_mae / baseline_mae}, i.e. how much better, not just how often. Counting winners
 * alone would let a 1000-model platform look healthy while each win is worth 6%.
 *
 * <p><b>Refreshed on a sweep, not on a scrape.</b> These numbers change exactly once a day,
 * when the trainer runs; recomputing them on every Prometheus scrape would put five thousand
 * daily COUNT queries behind an unauthenticated actuator endpoint to watch a value that cannot
 * have moved. They are seeded once at startup so a lunchtime restart does not read as
 * "every model lost" until 02:40.
 */
@Component
@ConditionalOnProperty(name = "finsight.forecast.model.enabled", havingValue = "true")
public class ForecastModelMetrics {

    private static final Logger log = LoggerFactory.getLogger(ForecastModelMetrics.class);

    private static final String MODELS = "finsight.analytics.forecast.models";
    private static final String ERROR_RATIO = "finsight.analytics.forecast.model.error.ratio";

    private static final BigDecimal WIN_FACTOR =
            BigDecimal.valueOf(1.0 - BacktestResult.REQUIRED_IMPROVEMENT);

    private final SpendingModelRepository repository;

    private final AtomicLong serving = new AtomicLong();
    private final AtomicLong beaten = new AtomicLong();
    private final AtomicLong unvalidated = new AtomicLong();
    /** Null until something has been scored; reported as NaN, which Prometheus reads as a gap. */
    private final AtomicReference<Double> errorRatio = new AtomicReference<>();

    public ForecastModelMetrics(SpendingModelRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;

        gauge(meterRegistry, "serving", serving,
                "Fitted spending models that beat the run rate on their holdout and are served");
        gauge(meterRegistry, "beaten", beaten,
                "Fitted spending models that lost to the run rate on their holdout");
        gauge(meterRegistry, "unvalidated", unvalidated,
                "Fitted spending models with too little history to score");

        Gauge.builder(ERROR_RATIO, errorRatio,
                        ref -> ref.get() == null ? Double.NaN : ref.get())
                .description("Mean model MAE divided by run-rate MAE over scored models; below 1 is better")
                .register(meterRegistry);
    }

    /** Recomputes every gauge from the table. Cheap, and called only at startup and after a sweep. */
    public void refresh() {
        try {
            long total = repository.count();
            long won = repository.countBeatingRunRate(WIN_FACTOR);
            long unscored = repository.countByModelMaeIsNull();

            serving.set(won);
            unvalidated.set(unscored);
            // Derived rather than queried: the three must add up to the total, and a fourth
            // query could disagree with the first three if a sweep landed between them.
            beaten.set(Math.max(0, total - won - unscored));
            errorRatio.set(repository.averageErrorRatio());
        } catch (Exception ex) {
            // Metrics must never be the reason a startup or a training sweep fails. Stale
            // gauges are a smaller problem than a service that will not boot.
            log.warn("Could not refresh forecast model metrics; leaving the previous values", ex);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        refresh();
    }

    private void gauge(MeterRegistry registry, String outcome, AtomicLong value, String description) {
        Gauge.builder(MODELS, value, AtomicLong::doubleValue)
                .tag("outcome", outcome)
                .description(description)
                .register(registry);
    }
}
