package com.pm.transactionservice.outbox;

import com.pm.transactionservice.logging.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for the outbox relay's drain logic (no broker, no DB — collaborators mocked). */
class OutboxRelayTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private OutboxRelay relay(boolean enabled) {
        return new OutboxRelay(repository, kafkaTemplate, enabled, 100, 5000);
    }

    private static OutboxEvent event(long id, String topic) {
        return event(id, topic, null);
    }

    private static OutboxEvent event(long id, String topic, String correlationId) {
        return OutboxEvent.builder()
                .id(id).eventId("evt-" + id).topic(topic).partitionKey("7")
                .eventType("TransactionCreated").payload("{\"id\":" + id + "}")
                .correlationId(correlationId)
                .build();
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(new SendResult<>(null, mock(RecordMetadata.class)));
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        return CompletableFuture.failedFuture(new RuntimeException("broker down"));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<ProducerRecord<String, String>> captureSends(int expected) {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(expected)).send(captor.capture());
        return captor;
    }

    private static String header(ProducerRecord<String, String> record) {
        var header = record.headers().lastHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void publishesEveryRowThenDeletesThemInOneBatch() {
        when(repository.findBatch(any())).thenReturn(List.of(event(1, "t1"), event(2, "t2")));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ok());

        relay(true).flush();

        List<ProducerRecord<String, String>> sent = captureSends(2).getAllValues();
        assertThat(sent).extracting(ProducerRecord::topic).containsExactly("t1", "t2");
        assertThat(sent).extracting(ProducerRecord::key).containsExactly("7", "7");
        assertThat(sent).extracting(ProducerRecord::value)
                .containsExactly("{\"id\":1}", "{\"id\":2}");
        verify(repository).deleteAllByIdInBatch(List.of(1L, 2L));
    }

    @Test
    void stopsAtFirstFailure_deletesOnlyRowsPublishedBefore_andNeverReordersAhead() {
        OutboxEvent e1 = event(1, "t1");
        OutboxEvent e2 = event(2, "t2"); // this send fails
        OutboxEvent e3 = event(3, "t3"); // must NOT be attempted (would reorder the user's stream)
        when(repository.findBatch(any())).thenReturn(List.of(e1, e2, e3));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<?, ?> record = invocation.getArgument(0);
            return "t2".equals(record.topic()) ? failed() : ok();
        });

        relay(true).flush();

        assertThat(captureSends(2).getAllValues()).extracting(ProducerRecord::topic)
                .containsExactly("t1", "t2");
        // Only the row confirmed before the failure is cleared; e2/e3 stay for the next tick.
        verify(repository).deleteAllByIdInBatch(List.of(1L));
    }

    @Test
    void disabled_doesNotTouchTheDatabaseOrBroker() {
        relay(false).flush();
        verifyNoInteractions(repository, kafkaTemplate);
    }

    @Test
    void emptyBatch_sendsNothingAndDeletesNothing() {
        when(repository.findBatch(any())).thenReturn(List.of());
        relay(true).flush();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(repository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    void replaysTheStoredCorrelationIdOntoTheRecordHeader() {
        when(repository.findBatch(any())).thenReturn(List.of(event(1, "t1", "corr-42")));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ok());

        relay(true).flush();

        assertThat(header(captureSends(1).getValue())).isEqualTo("corr-42");
    }

    @Test
    void addsNoHeaderWhenTheRowHasNoCorrelationId() {
        when(repository.findBatch(any())).thenReturn(List.of(event(1, "t1", null)));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(ok());

        relay(true).flush();

        assertThat(header(captureSends(1).getValue())).isNull();
    }

    @Test
    void restoresTheCorrelationIdInTheMdcWhilePublishing_andClearsItAfterwards() {
        // The relay runs on the scheduler thread: its own log lines only join the originating
        // request's trace if the MDC is restored around the send — and only that send.
        AtomicReference<String> duringSend = new AtomicReference<>();
        when(repository.findBatch(any())).thenReturn(List.of(event(1, "t1", "corr-42")));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            duringSend.set(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));
            return ok();
        });

        relay(true).flush();

        assertThat(duringSend.get()).isEqualTo("corr-42");
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY)).isNull();
    }
}
