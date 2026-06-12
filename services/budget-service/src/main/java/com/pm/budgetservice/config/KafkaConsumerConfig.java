package com.pm.budgetservice.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 */
@Configuration
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        // 3 attempts total (initial + 2 retries), 1s apart; then log and seek past.
        return new DefaultErrorHandler(new FixedBackOff(1000L, 2L));
    }
}
