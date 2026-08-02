package com.pm.budgetservice.event;

import com.pm.budgetservice.logging.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the BudgetChanged producer: routing, keying and correlation-id propagation. */
class KafkaBudgetEventPublisherTest {

    private static final String TOPIC = "finsight.budgets.changed";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, BudgetChangedEvent> kafkaTemplate = mock(KafkaTemplate.class);
    private final KafkaBudgetEventPublisher publisher = new KafkaBudgetEventPublisher(kafkaTemplate, TOPIC);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static BudgetChangedEvent event() {
        return new BudgetChangedEvent(UUID.randomUUID(), BudgetChangedEvent.EVENT_TYPE,
                "2026-08-01T00:00:00Z", UUID.randomUUID(), 42L, 3L, "VND",
                new BigDecimal("1000000"), "2026-08-01", "2026-08-31", "MONTHLY", false);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, BudgetChangedEvent> publish(BudgetChangedEvent event) {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new SendResult<>(null, mock(RecordMetadata.class))));

        publisher.publish(event);

        ArgumentCaptor<ProducerRecord<String, BudgetChangedEvent>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static String correlationHeader(ProducerRecord<String, BudgetChangedEvent> record) {
        var header = record.headers().lastHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void sendsToTheConfiguredTopicKeyedByUserId() {
        BudgetChangedEvent event = event();

        ProducerRecord<String, BudgetChangedEvent> record = publish(event);

        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(record.key()).isEqualTo("42");
        assertThat(record.value()).isSameAs(event);
    }

    @Test
    void carriesTheCurrentCorrelationIdAsARecordHeader() {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-42");

        assertThat(correlationHeader(publish(event()))).isEqualTo("corr-42");
    }

    @Test
    void addsNoHeaderWhenThereIsNoCorrelationIdInScope() {
        assertThat(correlationHeader(publish(event()))).isNull();
    }
}
