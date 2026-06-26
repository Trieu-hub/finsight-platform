package com.pm.notificationservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Error handling for the Kafka listener. Boot's auto-configuration picks this
 * {@code CommonErrorHandler} bean up for the default listener container factory.
 *
 * <p>A failing record is retried a few times (transient DB blips), then logged and
 * skipped so one poison message can never pin the partition. Deserialization failures
 * (the value deserializer is wrapped in {@code ErrorHandlingDeserializer} in
 * application.yml) are not retryable by nature and reach the same log-and-skip path.
 * No dead-letter topic at this scale — the log line carries the full record context.
 *
 * <p>Every skipped-after-retries record is counted in
 * {@code finsight.notifications.failed} via the recoverer, which the error handler
 * invokes exactly once after the back-off is exhausted and immediately before the
 * container seeks past the record. This is the retry-exhaustion casualty count and is
 * deliberately distinct from {@code ignored} (events we chose to skip by filter rule):
 * {@code failed} means the event should have produced a notification but never could, so
 * it is the one counter that signals a dropped alert.
 */
@Configuration
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(MeterRegistry meterRegistry) {
        // 3 attempts total (initial + 2 retries), 1s apart; then recover (count + log) and seek past.
        return new DefaultErrorHandler(failedEventRecoverer(meterRegistry), new FixedBackOff(1000L, 2L));
    }

    /**
     * Recoverer run once per record after retries are exhausted: increment the failure
     * counter and log full record context. Package-visible so it can be unit-tested
     * without standing up a container. Registering the counter eagerly here means it is
     * exported at {@code 0} from startup (a visible, alertable baseline) rather than
     * springing into existence only after the first failure.
     */
    static ConsumerRecordRecoverer failedEventRecoverer(MeterRegistry meterRegistry) {
        Counter failedEvents = Counter.builder("finsight.notifications.failed")
                .description("RiskDetected records skipped after retries were exhausted "
                        + "(poison message or persistent failure — signals a dropped alert)")
                .register(meterRegistry);
        return (record, exception) -> {
            failedEvents.increment();
            log.error("Skipping record after retries exhausted (event dropped): topic={}, "
                            + "partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception);
        };
    }
}
