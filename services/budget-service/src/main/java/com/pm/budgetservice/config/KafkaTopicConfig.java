package com.pm.budgetservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the {@code finsight.budgets.changed} topic this service now owns (Phase E.2).
 * {@code KafkaAdmin} creates it on startup when a broker is reachable (single-node dev
 * defaults: 1 partition, replication factor 1). Gated by {@code finsight.kafka.enabled} so
 * the broker-less test context never tries to create it.
 */
@Configuration
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic budgetChangedTopic(@Value("${finsight.kafka.topics.budget-changed}") String name) {
        return TopicBuilder.name(name)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
