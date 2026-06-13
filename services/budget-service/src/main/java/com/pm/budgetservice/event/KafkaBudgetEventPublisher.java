package com.pm.budgetservice.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka-backed {@link BudgetEventPublisher}. Sends each event to the configured topic keyed
 * by {@code userId}, so every budget change for a user lands on the same partition and stays
 * ordered relative to that user's other budget changes.
 *
 * <p>The send is asynchronous; a delivery failure is logged, not rethrown — the budget
 * transaction has already committed by the time this runs (see {@link BudgetEventListener}),
 * so failing here would not roll anything back. Guaranteed delivery (a transactional outbox)
 * is a later hardening step, consistent with transaction-service's Phase 2.1 stance.
 */
@Component
public class KafkaBudgetEventPublisher implements BudgetEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaBudgetEventPublisher.class);

    private final KafkaTemplate<String, BudgetChangedEvent> kafkaTemplate;
    private final String topic;

    public KafkaBudgetEventPublisher(KafkaTemplate<String, BudgetChangedEvent> kafkaTemplate,
                                     @Value("${finsight.kafka.topics.budget-changed}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(BudgetChangedEvent event) {
        String key = String.valueOf(event.userId());
        kafkaTemplate.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish BudgetChanged event eventId={} budgetId={}",
                        event.eventId(), event.budgetId(), ex);
            } else if (log.isDebugEnabled()) {
                var md = result.getRecordMetadata();
                log.debug("Published BudgetChanged event eventId={} budgetId={} to {}-{}@{}",
                        event.eventId(), event.budgetId(), md.topic(), md.partition(), md.offset());
            }
        });
    }
}
