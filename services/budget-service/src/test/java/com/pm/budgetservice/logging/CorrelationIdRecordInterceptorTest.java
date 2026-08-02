package com.pm.budgetservice.logging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Kafka-side correlation id propagation. The MDC is asserted straight after
 * {@code intercept} because that is exactly when the listener runs and logs.
 */
class CorrelationIdRecordInterceptorTest {

    private final CorrelationIdRecordInterceptor<String, String> interceptor =
            new CorrelationIdRecordInterceptor<>();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static ConsumerRecord<String, String> record(String correlationId) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("finsight.transactions.created", 0, 0L, "7", "{}");
        if (correlationId != null) {
            record.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    private static String mdc() {
        return MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
    }

    @Test
    void putsTheProducersCorrelationIdInTheMdcForTheRecord() {
        interceptor.intercept(record("corr-42"), null);

        assertThat(mdc()).isEqualTo("corr-42");
    }

    @Test
    void returnsTheRecordUnchanged() {
        ConsumerRecord<String, String> incoming = record("corr-42");

        assertThat(interceptor.intercept(incoming, null)).isSameAs(incoming);
    }

    @Test
    void generatesAnIdWhenTheProducerSetNoHeader() {
        // A consumer log line without any id would be unsearchable — worse than a fresh one.
        interceptor.intercept(record(null), null);

        assertThat(mdc()).isNotBlank();
    }

    @Test
    void generatesAnIdWhenTheHeaderIsBlank() {
        interceptor.intercept(record("   "), null);

        assertThat(mdc()).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    void clearsTheMdcAfterTheRecord() {
        // Listener threads are pooled: a leaked id would mislabel every later record.
        ConsumerRecord<String, String> incoming = record("corr-42");
        interceptor.intercept(incoming, null);

        interceptor.afterRecord(incoming, null);

        assertThat(mdc()).isNull();
    }
}
