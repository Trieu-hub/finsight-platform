package com.pm.transactionservice.outbox;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    private OutboxRelay relay(boolean enabled) {
        return new OutboxRelay(repository, kafkaTemplate, enabled, 100, 5000);
    }

    private static OutboxEvent event(long id, String topic) {
        return OutboxEvent.builder()
                .id(id).eventId("evt-" + id).topic(topic).partitionKey("7")
                .eventType("TransactionCreated").payload("{\"id\":" + id + "}")
                .build();
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(new SendResult<>(null, mock(RecordMetadata.class)));
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        return CompletableFuture.failedFuture(new RuntimeException("broker down"));
    }

    @Test
    void publishesEveryRowThenDeletesThemInOneBatch() {
        OutboxEvent e1 = event(1, "t1");
        OutboxEvent e2 = event(2, "t2");
        when(repository.findBatch(any())).thenReturn(List.of(e1, e2));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(ok());

        relay(true).flush();

        verify(kafkaTemplate).send("t1", "7", "{\"id\":1}");
        verify(kafkaTemplate).send("t2", "7", "{\"id\":2}");
        verify(repository).deleteAllByIdInBatch(List.of(1L, 2L));
    }

    @Test
    void stopsAtFirstFailure_deletesOnlyRowsPublishedBefore_andNeverReordersAhead() {
        OutboxEvent e1 = event(1, "t1");
        OutboxEvent e2 = event(2, "t2"); // this send fails
        OutboxEvent e3 = event(3, "t3"); // must NOT be attempted (would reorder the user's stream)
        when(repository.findBatch(any())).thenReturn(List.of(e1, e2, e3));
        when(kafkaTemplate.send(eq("t1"), any(), any())).thenReturn(ok());
        when(kafkaTemplate.send(eq("t2"), any(), any())).thenReturn(failed());

        relay(true).flush();

        verify(kafkaTemplate).send(eq("t1"), any(), any());
        verify(kafkaTemplate).send(eq("t2"), any(), any());
        verify(kafkaTemplate, never()).send(eq("t3"), any(), any());
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
        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(repository, never()).deleteAllByIdInBatch(any());
    }
}
