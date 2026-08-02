package com.pm.analyticsservice.config;

import com.pm.analyticsservice.logging.CorrelationIdRecordInterceptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Error handling for the Kafka listener. Boot's auto-configuration picks this
 * {@code CommonErrorHandler} bean up for the default listener container factory.
 *
 * <p>A failing record is retried a few times (transient DB blips), then logged and
 * skipped so one poison message can never pin the partition. Deserialization failures
 * (the value deserializer is wrapped in {@code ErrorHandlingDeserializer} in
 * application.yml) reach the same log-and-skip path. No dead-letter topic at this scale.
 *
 * <p>Every skipped-after-retries record is counted in {@code finsight.analytics.failed}.
 */
@Configuration
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /**
     * Carries the producer's correlation id from the record header into the MDC for the duration of
     * each consumed record. Boot applies a single {@code RecordInterceptor} bean to the
     * auto-configured listener container factory, which is the one the rollup listener uses.
     */
    @Bean
    public RecordInterceptor<Object, Object> correlationIdRecordInterceptor() {
        return new CorrelationIdRecordInterceptor<>();
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(MeterRegistry meterRegistry) {
        // 3 attempts total (initial + 2 retries), 1s apart; then recover (count + log) and seek past.
        return new DefaultErrorHandler(failedEventRecoverer(meterRegistry), new FixedBackOff(1000L, 2L));
    }

    static ConsumerRecordRecoverer failedEventRecoverer(MeterRegistry meterRegistry) {
        Counter failedEvents = Counter.builder("finsight.analytics.failed")
                .description("TransactionCreated records skipped after retries were exhausted "
                        + "(poison message or persistent failure — signals a rollup gap)")
                .register(meterRegistry);
        return (record, exception) -> {
            failedEvents.increment();
            log.error("Skipping record after retries exhausted (rollup gap): topic={}, "
                            + "partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception);
        };
    }
}
