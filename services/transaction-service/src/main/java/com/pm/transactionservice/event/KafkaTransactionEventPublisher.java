package com.pm.transactionservice.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka-backed {@link TransactionEventPublisher}. Sends each event to the configured
 * topic keyed by {@code userId}, so every event for a given user lands on the same
 * partition and stays strictly ordered — the property downstream per-user anomaly /
 * risk consumers will rely on.
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

    private final KafkaTemplate<String, TransactionCreatedEvent> kafkaTemplate;
    private final String topic;

    public KafkaTransactionEventPublisher(
            KafkaTemplate<String, TransactionCreatedEvent> kafkaTemplate,
            @Value("${finsight.kafka.topics.transaction-created}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(TransactionCreatedEvent event) {
        String key = String.valueOf(event.userId());
        kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish TransactionCreated event eventId={} transactionId={}",
                        event.eventId(), event.transactionId(), ex);
            } else if (log.isDebugEnabled()) {
                var md = result.getRecordMetadata();
                log.debug("Published TransactionCreated event eventId={} transactionId={} to {}-{}@{}",
                        event.eventId(), event.transactionId(), md.topic(), md.partition(), md.offset());
            }
        });
    }
}
