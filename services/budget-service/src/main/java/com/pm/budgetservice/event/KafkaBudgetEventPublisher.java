package com.pm.budgetservice.event;

import com.pm.budgetservice.logging.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

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

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String changedTopic;
    private final String exceededTopic;

    public KafkaBudgetEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                     @Value("${finsight.kafka.topics.budget-changed}") String changedTopic,
                                     @Value("${finsight.kafka.topics.budget-exceeded}") String exceededTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.changedTopic = changedTopic;
        this.exceededTopic = exceededTopic;
    }

    @Override
    public void publish(BudgetChangedEvent event) {
        send(changedTopic, String.valueOf(event.userId()), event,
                "BudgetChanged", event.eventId(), event.budgetId());
    }

    @Override
    public void publishExceeded(BudgetExceededEvent event) {
        send(exceededTopic, String.valueOf(event.userId()), event,
                "BudgetExceeded", event.eventId(), event.budgetId());
    }

    private void send(String topic, String key, Object event,
                      String name, java.util.UUID eventId, java.util.UUID budgetId) {
        kafkaTemplate.send(toRecord(topic, key, event)).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} event eventId={} budgetId={}", name, eventId, budgetId, ex);
            } else if (log.isDebugEnabled()) {
                var md = result.getRecordMetadata();
                log.debug("Published {} event eventId={} budgetId={} to {}-{}@{}",
                        name, eventId, budgetId, md.topic(), md.partition(), md.offset());
            }
        });
    }

    /**
     * The record to send: same topic and key as before, plus the current correlation id as a
     * {@value CorrelationIdFilter#CORRELATION_ID_HEADER} header so risk-service's log lines for this
     * budget change rejoin the trace of whatever caused it. The id comes from the MDC, which is
     * populated either by {@link CorrelationIdFilter} (a REST budget write — this publisher runs on
     * the request thread, after commit) or by the record interceptor when the change was itself
     * driven by a consumed event. Null when neither applies; then no header is added.
     */
    ProducerRecord<String, Object> toRecord(String topic, String key, Object event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, event);
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            record.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
