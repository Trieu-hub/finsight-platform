package com.pm.riskservice.config;

import com.pm.riskservice.event.BudgetChangedEvent;
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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring. Boot auto-configures the <em>default</em> listener container factory
 * (TransactionCreated, from {@code spring.kafka.consumer.*}) and picks up the
 * {@link DefaultErrorHandler} bean below for it; the budget read-model factory reuses the same
 * handler.
 *
 * <p>A failing record is retried a few times (transient blips), then recovered (counted + logged)
 * and skipped so one poison message can never pin the partition. Deserialization failures (the
 * value deserializer is wrapped in {@code ErrorHandlingDeserializer}) are not retryable and reach
 * the same log-and-skip path. No dead-letter topic at this scale — the log line carries the record
 * context.
 *
 * <p>Every record skipped after retries are exhausted is counted in
 * {@code finsight.risk.events.failed} via the recoverer (run exactly once per record, immediately
 * before the container seeks past it). This is the retry-exhaustion casualty count — an event that
 * should have driven a risk rule / insight / anomaly but never could, so it is the one counter that
 * signals real loss on the risk path. It mirrors budget-service's {@code finsight.budget.events.failed}.
 *
 * <p>{@code BudgetChanged} (Phase E.2) is a different type on a different topic, and the wire
 * format is headerless (one JSON default type per consumer factory), so it needs its own
 * container factory rather than sharing the auto-configured one.
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
     * Recoverer run once per record after retries are exhausted: increment the failure counter and
     * log full record context. Package-visible so it can be unit-tested without standing up a
     * container. Registering the counter eagerly here means it is exported at {@code 0} from
     * startup (a visible, alertable baseline) rather than appearing only after the first failure.
     */
    static ConsumerRecordRecoverer failedEventRecoverer(MeterRegistry meterRegistry) {
        Counter failedEvents = Counter.builder("finsight.risk.events.failed")
                .description("Consumed events skipped after retries were exhausted "
                        + "(poison message or persistent failure — signals a dropped risk input)")
                .register(meterRegistry);
        return (record, exception) -> {
            failedEvents.increment();
            log.error("Skipping record after retries exhausted (event dropped): topic={}, "
                            + "partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception);
        };
    }

    /**
     * Dedicated factory for the budget read-model listener: a separate consumer group reading
     * {@code finsight.budgets.changed} from the beginning, deserializing headerless JSON into
     * {@link BudgetChangedEvent}. Reuses the same retry/skip error handler.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BudgetChangedEvent>
            budgetEventListenerContainerFactory(
                    @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                    DefaultErrorHandler kafkaErrorHandler) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Distinct group so budget consumption tracks its own offsets independently.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "risk-service-budgets");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        // Headerless wire format: the target type must be declared, type headers ignored.
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BudgetChangedEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.pm.riskservice.event");

        ConsumerFactory<String, BudgetChangedEvent> consumerFactory =
                new DefaultKafkaConsumerFactory<>(props);
        ConcurrentKafkaListenerContainerFactory<String, BudgetChangedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
