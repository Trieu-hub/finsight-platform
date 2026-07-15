package com.pm.transactionservice.event;

import com.pm.transactionservice.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes each transaction domain event into the transactional <b>outbox</b>. The service publishes
 * a domain event (e.g. {@link TransactionCreatedEvent}) inside its {@code @Transactional} method;
 * this listener fires at {@link TransactionPhase#BEFORE_COMMIT}, so the outbox row is inserted as
 * part of that same transaction and commits (or rolls back) atomically with the transaction it
 * describes. A separate {@link com.pm.transactionservice.outbox.OutboxRelay} then publishes the row
 * to Kafka.
 *
 * <p>This closes the AFTER_COMMIT dual-write gap (ADR-0004): previously the Kafka send ran after
 * commit, so a committed change whose send then failed was lost. Now the event cannot be lost once
 * the transaction commits, and it is never emitted for a transaction that rolled back.
 */
@Component
public class TransactionEventListener {

    private final OutboxWriter outboxWriter;
    private final boolean kafkaEnabled;
    private final String createdTopic;
    private final String updatedTopic;
    private final String deletedTopic;

    public TransactionEventListener(OutboxWriter outboxWriter,
                                    @Value("${finsight.kafka.enabled:true}") boolean kafkaEnabled,
                                    @Value("${finsight.kafka.topics.transaction-created}") String createdTopic,
                                    @Value("${finsight.kafka.topics.transaction-updated}") String updatedTopic,
                                    @Value("${finsight.kafka.topics.transaction-deleted}") String deletedTopic) {
        this.outboxWriter = outboxWriter;
        this.kafkaEnabled = kafkaEnabled;
        this.createdTopic = createdTopic;
        this.updatedTopic = updatedTopic;
        this.deletedTopic = deletedTopic;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTransactionCreated(TransactionCreatedEvent event) {
        // Master switch off (e.g. the MySQL-only integration tests): produce no events at all,
        // so no outbox rows accumulate either.
        if (!kafkaEnabled) {
            return;
        }
        outboxWriter.append(createdTopic, String.valueOf(event.userId()),
                event.eventType(), event.eventId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTransactionUpdated(TransactionUpdatedEvent event) {
        if (!kafkaEnabled) {
            return;
        }
        outboxWriter.append(updatedTopic, String.valueOf(event.userId()),
                event.eventType(), event.eventId().toString(), event);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTransactionDeleted(TransactionDeletedEvent event) {
        if (!kafkaEnabled) {
            return;
        }
        outboxWriter.append(deletedTopic, String.valueOf(event.userId()),
                event.eventType(), event.eventId().toString(), event);
    }
}
