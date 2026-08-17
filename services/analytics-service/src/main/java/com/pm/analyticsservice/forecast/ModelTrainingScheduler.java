package com.pm.analyticsservice.forecast;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Retrains every user's spending model overnight.
 *
 * <p>Training is a sweep rather than an event handler for the same reason the monthly report
 * is: nothing publishes "yesterday finished". It always trains up to <em>yesterday</em>, so
 * the window only ever contains whole days — training up to today would feed the fit a
 * partial day whose low total looks like a collapse in spending.
 *
 * <p><b>Single instance</b>, like {@code MonthlyReportScheduler} and transaction-service's
 * outbox relay: two would fit the same users concurrently and race on the upsert.
 *
 * <p>Gated on {@code finsight.forecast.model.enabled}, <b>off by default</b>. A first sweep
 * walks every user's window at once, and the forecast silently changes shape for everyone the
 * moment models exist — that is a decision to make deliberately, not a side effect of the
 * deploy that ships it. With the flag off, no model rows are written and the forecast keeps
 * answering from the run rate exactly as before.
 */
@Component
@ConditionalOnProperty(name = "finsight.forecast.model.enabled", havingValue = "true")
public class ModelTrainingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ModelTrainingScheduler.class);

    private final SpendingModelTrainer trainer;
    private final ForecastModelMetrics metrics;
    private final Counter trainedCounter;
    private final Counter failedCounter;

    public ModelTrainingScheduler(SpendingModelTrainer trainer,
                                  ForecastModelMetrics metrics,
                                  MeterRegistry meterRegistry) {
        this.trainer = trainer;
        this.metrics = metrics;
        this.trainedCounter = Counter.builder("finsight.analytics.forecast.models.trained")
                .description("Spending models fitted and persisted")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("finsight.analytics.forecast.training.failed")
                .description("Training sweeps that ended in an error")
                .register(meterRegistry);
    }

    /** 02:40 daily — before the 03:20 monthly report sweep, after the nightly backup window. */
    @Scheduled(cron = "${finsight.forecast.model.cron:0 40 2 * * *}")
    public void train() {
        LocalDate trainedUpto = LocalDate.now().minusDays(1);
        try {
            int written = trainer.trainAll(trainedUpto);
            trainedCounter.increment(written);
            // The sweep is the only thing that changes what the gauges measure, so this is the
            // one moment they need re-reading.
            metrics.refresh();
        } catch (Exception ex) {
            // A failed sweep must not kill the scheduler thread: the models simply stay at
            // yesterday's fit and the next run retries.
            failedCounter.increment();
            log.error("Spending model training failed for window ending {}", trainedUpto, ex);
        }
    }
}
