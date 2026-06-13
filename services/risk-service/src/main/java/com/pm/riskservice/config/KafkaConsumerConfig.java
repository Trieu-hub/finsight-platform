package com.pm.riskservice.config;

import com.pm.riskservice.event.BudgetChangedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring. Boot auto-configures the <em>default</em> listener container factory
 * (TransactionCreated, from {@code spring.kafka.consumer.*}) and picks up the
 * {@link DefaultErrorHandler} bean below for it.
 *
 * <p>A failing record is retried a few times (transient blips), then logged and skipped so one
 * poison message can never pin the partition. Deserialization failures (the value deserializer
 * is wrapped in {@code ErrorHandlingDeserializer}) are not retryable and reach the same
 * log-and-skip path. No dead-letter topic at this scale — the log line carries the record context.
 *
 * <p>{@code BudgetChanged} (Phase E.2) is a different type on a different topic, and the wire
 * format is headerless (one JSON default type per consumer factory), so it needs its own
 * container factory rather than sharing the auto-configured one.
 */
@Configuration
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class KafkaConsumerConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        // 3 attempts total (initial + 2 retries), 1s apart; then log and seek past.
        return new DefaultErrorHandler(new FixedBackOff(1000L, 2L));
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
