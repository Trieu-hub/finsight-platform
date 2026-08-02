package com.pm.transactionservice.outbox;

import com.pm.transactionservice.logging.CorrelationIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains the {@code outbox} table to Kafka. Runs on a fixed schedule: reads the oldest pending
 * events (id order), publishes each and deletes it once the broker acknowledges.
 *
 * <p><b>Delivery semantics.</b> A row is deleted only after a confirmed send, so a crash between
 * send and delete re-publishes it — at-least-once. Downstream consumers de-duplicate on the stable
 * {@code eventId} (idempotency inbox), giving effectively-once end to end.
 *
 * <p><b>Ordering.</b> On the first send failure the batch stops and the remaining rows are left for
 * the next tick, so a user's events are never reordered by skipping a stuck one.
 *
 * <p>Single-instance by design (this deploy runs one replica). Scaling out would need row locking
 * — {@code SELECT ... FOR UPDATE SKIP LOCKED} — so two relays never grab the same rows.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean enabled;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxRepository repository,
                       @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${finsight.kafka.enabled:true}") boolean enabled,
                       @Value("${finsight.outbox.batch-size:100}") int batchSize,
                       @Value("${finsight.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${finsight.outbox.poll-interval-ms:1000}")
    public void flush() {
        // Off where there is no broker (e.g. the MySQL-only integration tests). The outbox rows are
        // simply left in place; nothing tries to reach Kafka.
        if (!enabled) {
            return;
        }

        List<OutboxEvent> batch = repository.findBatch(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }

        List<Long> published = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            // Runs on the scheduler thread, whose MDC is empty: restore the writing request's id so
            // the relay's own lines about this event sit in that request's trace too.
            if (event.getCorrelationId() != null) {
                MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, event.getCorrelationId());
            }
            try {
                kafkaTemplate.send(toRecord(event)).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                published.add(event.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted while publishing eventId={}", event.getEventId());
                break;
            } catch (Exception e) {
                // Broker down / send timed out: stop here, keep this and later rows for the next
                // tick. Never skip ahead — that would reorder the user's events.
                log.warn("Outbox relay: failed to publish eventId={} to {}, will retry",
                        event.getEventId(), event.getTopic(), e);
                break;
            } finally {
                MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
            }
        }

        if (!published.isEmpty()) {
            repository.deleteAllByIdInBatch(published);
            if (log.isDebugEnabled()) {
                log.debug("Outbox relay published and cleared {} event(s)", published.size());
            }
        }
    }

    /**
     * The record to publish: payload verbatim, keyed as before, plus the stored correlation id as a
     * {@value CorrelationIdFilter#CORRELATION_ID_HEADER} header when the writing request had one.
     * The header is the only thing that lets a consumer's log lines rejoin that request's trace —
     * the JSON payload is a stable public contract and must not grow a tracing field.
     */
    static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getPartitionKey(), event.getPayload());
        if (event.getCorrelationId() != null) {
            record.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                    event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
