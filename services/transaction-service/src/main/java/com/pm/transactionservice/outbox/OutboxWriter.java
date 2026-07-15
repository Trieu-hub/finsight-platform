package com.pm.transactionservice.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Persists a domain event into the {@code outbox} table. Called from within the business
 * transaction (see {@link com.pm.transactionservice.event.TransactionEventListener}), so the row
 * is committed atomically with the transaction it describes — or rolled back with it.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    // A private, isolated mapper. The events carry no java.time types on the wire (dates are
    // pre-formatted Strings) and enums serialize by name — a plain mapper reproduces exactly the
    // JSON the direct Kafka producer used to send. Not the shared web ObjectMapper, so outbox
    // serialization can never perturb (or be perturbed by) the API's JSON configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OutboxWriter(OutboxRepository repository) {
        this.repository = repository;
    }

    /**
     * @param topic     Kafka topic the relay will publish to
     * @param key       partition key (userId as string) — keeps a user's events ordered
     * @param eventType stable discriminator, e.g. {@code TransactionCreated}
     * @param eventId   the domain event's id (downstream dedup key), stored for tracing
     * @param payload   the event object; serialized to JSON here and sent to Kafka verbatim
     */
    public void append(String topic, String key, String eventType, String eventId, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // A well-defined event record does not fail to serialize; if it somehow does, fail the
            // whole business transaction rather than persist an event we can never publish.
            throw new IllegalStateException("Failed to serialize outbox payload for eventId=" + eventId, e);
        }
        repository.save(OutboxEvent.builder()
                .eventId(eventId)
                .topic(topic)
                .partitionKey(key)
                .eventType(eventType)
                .payload(json)
                .build());
    }
}
