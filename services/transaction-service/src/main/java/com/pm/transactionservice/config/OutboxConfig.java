package com.pm.transactionservice.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

/**
 * Wiring for the transactional outbox: enables the scheduled {@link com.pm.transactionservice.outbox.OutboxRelay}
 * and supplies its Kafka template.
 *
 * <p>The relay uses a <b>String</b>-valued template (not the auto-configured JSON one) because the
 * outbox already stores each event as a JSON string; a {@link StringSerializer} puts those exact
 * bytes on the wire, so the topic payload is byte-for-byte what the direct producer used to send —
 * the consumer contract is unchanged. {@code acks=all} keeps the send durable.
 */
@Configuration
@EnableScheduling
public class OutboxConfig {

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // Fail a send fast if the broker is unreachable; the relay retries on the next tick.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }
}
