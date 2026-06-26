package com.pm.notificationservice.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry-exhaustion recoverer increments {@code finsight.notifications.failed} once
 * per record it gives up on. Verified without a broker by invoking the recoverer
 * directly — the same callback {@code DefaultErrorHandler} runs after the back-off is
 * exhausted.
 */
class KafkaConsumerConfigTest {

    private static final String FAILED = "finsight.notifications.failed";

    private SimpleMeterRegistry meterRegistry;
    private ConsumerRecordRecoverer recoverer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        recoverer = KafkaConsumerConfig.failedEventRecoverer(meterRegistry);
    }

    @Test
    void counterRegisteredAtZeroBeforeAnyFailure() {
        assertThat(meterRegistry.counter(FAILED).count()).isEqualTo(0.0);
    }

    @Test
    void eachRecoveredRecordIncrementsFailedOnce() {
        recoverer.accept(record(0L), new IllegalStateException("boom"));
        recoverer.accept(record(1L), new IllegalStateException("boom again"));

        assertThat(meterRegistry.counter(FAILED).count()).isEqualTo(2.0);
    }

    private ConsumerRecord<Object, Object> record(long offset) {
        return new ConsumerRecord<>("finsight.transactions.created", 0, offset, 42L, "payload");
    }
}
