package com.pm.transactionservice.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kafka-backed {@link TransactionEventPublisher}. Sends each event to its topic keyed by
 * {@code userId}, so every event for a given user lands on the same partition and stays
 * strictly ordered — the property downstream per-user anomaly / risk consumers rely on.
 * The three transaction-lifecycle topics (created / updated / deleted) are distinct, so a
 * consumer that only cares about one kind (risk, analytics) subscribes to just that topic.
 *
 * <p>The send is asynchronous; a callback logs success (topic/partition/offset) or
 * failure. A delivery failure is logged, not rethrown: the transaction has already
 * committed by the time this runs (see {@code TransactionEventListener}), so failing
 * here would not roll anything back. Guaranteed delivery is a later hardening step
 * (transactional outbox).
 */
@Component
public class KafkaTransactionEventPublisher implements TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransactionEventPublisher.class);

    // Typed as Object because this one template carries three distinct event records; the
    // JsonSerializer serializes any of them. Generics are compile-time only, so the single
    // auto-configured KafkaTemplate bean injects here regardless of its declared parameters.
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String createdTopic;
    private final String updatedTopic;
    private final String deletedTopic;

    public KafkaTransactionEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${finsight.kafka.topics.transaction-created}") String createdTopic,
            @Value("${finsight.kafka.topics.transaction-updated}") String updatedTopic,
            @Value("${finsight.kafka.topics.transaction-deleted}") String deletedTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.createdTopic = createdTopic;
        this.updatedTopic = updatedTopic;
        this.deletedTopic = deletedTopic;
    }

    @Override
    public void publish(TransactionCreatedEvent event) {
        send(createdTopic, event.userId(), event, "TransactionCreated",
                event.eventId(), event.transactionId());
    }

    @Override
    public void publish(TransactionUpdatedEvent event) {
        send(updatedTopic, event.userId(), event, "TransactionUpdated",
                event.eventId(), event.transactionId());
    }

    @Override
    public void publish(TransactionDeletedEvent event) {
        send(deletedTopic, event.userId(), event, "TransactionDeleted",
                event.eventId(), event.transactionId());
    }

    private void send(String topic, Long userId, Object event, String kind,
                      UUID eventId, UUID transactionId) {
        String key = String.valueOf(userId);
        kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} event eventId={} transactionId={}",
                        kind, eventId, transactionId, ex);
            } else if (log.isDebugEnabled()) {
                var md = result.getRecordMetadata();
                log.debug("Published {} event eventId={} transactionId={} to {}-{}@{}",
                        kind, eventId, transactionId, md.topic(), md.partition(), md.offset());
            }
        });
    }
}
