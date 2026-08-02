package com.pm.transactionservice.outbox;

import com.pm.transactionservice.logging.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Unit tests for the outbox writer (no DB — the repository is mocked). */
class OutboxWriterTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxWriter writer = new OutboxWriter(repository);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private OutboxEvent appended() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void serializesThePayloadAndCarriesTheRoutingFieldsThrough() {
        writer.append("t1", "7", "TransactionCreated", "evt-1", Map.of("amount", 12));

        OutboxEvent saved = appended();
        assertThat(saved.getTopic()).isEqualTo("t1");
        assertThat(saved.getPartitionKey()).isEqualTo("7");
        assertThat(saved.getEventType()).isEqualTo("TransactionCreated");
        assertThat(saved.getEventId()).isEqualTo("evt-1");
        assertThat(saved.getPayload()).isEqualTo("{\"amount\":12}");
    }

    @Test
    void capturesTheRequestsCorrelationIdFromTheMdc() {
        // Captured here because the relay publishes later, on a thread whose MDC is empty.
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-42");

        writer.append("t1", "7", "TransactionCreated", "evt-1", Map.of("amount", 12));

        assertThat(appended().getCorrelationId()).isEqualTo("corr-42");
    }

    @Test
    void storesNoCorrelationIdWhenTheEventIsProducedOutsideARequest() {
        writer.append("t1", "7", "TransactionCreated", "evt-1", Map.of("amount", 12));

        assertThat(appended().getCorrelationId()).isNull();
    }
}
