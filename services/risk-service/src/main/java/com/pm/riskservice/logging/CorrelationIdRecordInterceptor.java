package com.pm.riskservice.logging;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The Kafka-side counterpart of {@link CorrelationIdFilter}: puts the producing request's
 * correlation id into the MDC for the duration of one consumed record, so this service's log lines
 * can be tied back to the HTTP request that ultimately caused the event. Without it a consumer's
 * lines are orphaned — the async hop is exactly where a cross-service trace used to break.
 *
 * <p>Reuses the {@value CorrelationIdFilter#CORRELATION_ID_HEADER} record header when the producer
 * set one, and generates an id when it is missing or blank, so no consumer line is ever without
 * one. The MDC entry is removed in {@link #afterRecord} — listener threads are long-lived and
 * shared, so a leaked id would mislabel every later record on that thread.
 *
 * <p>Registered in {@code KafkaConsumerConfig} twice over: as the bean Boot applies to the
 * auto-configured (TransactionCreated) factory, and by hand on the budget factory built there.
 */
public class CorrelationIdRecordInterceptor<K, V> implements RecordInterceptor<K, V> {

    @Override
    public ConsumerRecord<K, V> intercept(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationIdOf(record));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<K, V> record, Consumer<K, V> consumer) {
        MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
    }

    private static String correlationIdOf(ConsumerRecord<?, ?> record) {
        Header header = record.headers().lastHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        if (header == null || header.value() == null || header.value().length == 0) {
            return UUID.randomUUID().toString();
        }
        String correlationId = new String(header.value(), StandardCharsets.UTF_8);
        return correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
    }
}
