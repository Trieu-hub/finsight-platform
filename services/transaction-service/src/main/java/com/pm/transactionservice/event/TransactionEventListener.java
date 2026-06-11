package com.pm.transactionservice.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the in-process domain event to the external Kafka event, firing only
 * {@link TransactionPhase#AFTER_COMMIT}. The service publishes a {@link TransactionCreatedEvent}
 * as a Spring application event inside the create transaction; this listener forwards it
 * to Kafka <em>after</em> that transaction commits.
 *
 * <p>This guarantees we never emit a {@code TransactionCreated} for a transaction that
 * rolled back. (The remaining dual-write gap — committed in the DB but the broker send
 * later fails — is accepted for Phase 2.1 and is the job of a future transactional outbox.)
 */
@Component
public class TransactionEventListener {

    private final TransactionEventPublisher publisher;
    private final boolean kafkaEnabled;

    public TransactionEventListener(TransactionEventPublisher publisher,
                                    @Value("${finsight.kafka.enabled:true}") boolean kafkaEnabled) {
        this.publisher = publisher;
        this.kafkaEnabled = kafkaEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCreated(TransactionCreatedEvent event) {
        // Off in environments with no broker (e.g. the MySQL-only integration tests),
        // so create() never blocks on producer metadata. Defaults on for compose/prod.
        if (!kafkaEnabled) {
            return;
        }
        publisher.publish(event);
    }
}
