package com.pm.notificationservice.config;

import com.pm.notificationservice.event.BudgetExceededEvent;
import com.pm.notificationservice.event.RiskDetectedEvent;
import com.pm.notificationservice.logging.CorrelationIdRecordInterceptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

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

    /**
     * Carries the producer's correlation id from the record header into the MDC for the duration of
     * each consumed record. Boot applies a single {@code RecordInterceptor} bean to the
     * auto-configured listener container factory, which is the one the RiskDetected listener uses.
     * The hand-built BudgetExceeded factory below is outside Boot's reach and sets its own.
     */
    @Bean
    public RecordInterceptor<Object, Object> correlationIdRecordInterceptor() {
        return new CorrelationIdRecordInterceptor<>();
    }

    /**
     * Dedicated factory for {@code BudgetExceeded}: a second topic carrying a different type, and
     * the wire format is headerless — one JSON default type per consumer factory — so it cannot
     * share the auto-configured one, which is pinned to {@link RiskDetectedEvent}.
     *
     * <p>Its own consumer group, so budget alerts track offsets independently of risk alerts and a
     * replay of one never replays the other.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BudgetExceededEvent>
            budgetExceededListenerContainerFactory(
                    @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                    DefaultErrorHandler kafkaErrorHandler,
                    MeterRegistry meterRegistry) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-service-budgets");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BudgetExceededEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.pm.notificationservice.event");

        DefaultKafkaConsumerFactory<String, BudgetExceededEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(props);
        // Boot's metrics customizer only reaches the auto-configured factory, so bind the native
        // client metrics (consumer lag included) for this group explicitly.
        consumerFactory.addListener(new MicrometerConsumerListener<>(meterRegistry));

        ConcurrentKafkaListenerContainerFactory<String, BudgetExceededEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // Attached by hand for the same reason as risk-service's budget factory: Boot's configurer
        // never touches this one, and without it BudgetExceeded would be the single consumed event
        // whose log lines drop out of the cross-service trace.
        factory.setRecordInterceptor(new CorrelationIdRecordInterceptor<>());
        return factory;
    }

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
