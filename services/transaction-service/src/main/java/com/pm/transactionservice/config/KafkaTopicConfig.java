package com.pm.transactionservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service owns. {@code KafkaAdmin} creates any {@link NewTopic}
 * bean on startup when a broker is reachable (single-node dev defaults: 1 partition,
 * replication factor 1). Topic auto-creation is disabled in the test profile so the
 * MySQL-only integration tests never reach for a broker.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transactionCreatedTopic(
            @Value("${finsight.kafka.topics.transaction-created}") String name) {
        return TopicBuilder.name(name)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
