package com.pm.analyticsservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the {@code finsight.reports.monthly} topic this service owns (Phase G.2) —
 * analytics-service's first produced event. {@code KafkaAdmin} creates it on startup when a
 * broker is reachable (single-node dev defaults: 1 partition, replication factor 1). Gated by
 * {@code finsight.kafka.enabled} so the broker-less test context never tries.
 */
@Configuration
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    public NewTopic monthlyReportTopic(@Value("${finsight.kafka.topics.monthly-report}") String name) {
        return TopicBuilder.name(name)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
