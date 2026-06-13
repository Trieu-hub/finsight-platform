package com.pm.budgetservice.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the in-process budget domain event to the external Kafka event, firing only
 * {@link TransactionPhase#AFTER_COMMIT}. The service publishes a {@link BudgetChangedEvent}
 * as a Spring application event inside the create/update/delete transaction; this listener
 * forwards it to Kafka <em>after</em> that transaction commits, so a rolled-back change is
 * never emitted.
 *
 * <p>The remaining dual-write gap (committed in the DB but the broker send later fails) is
 * accepted here, mirroring transaction-service; a transactional outbox is the future fix.
 */
@Component
public class BudgetEventListener {

    private final BudgetEventPublisher publisher;
    private final boolean kafkaEnabled;

    public BudgetEventListener(BudgetEventPublisher publisher,
                               @Value("${finsight.kafka.enabled:true}") boolean kafkaEnabled) {
        this.publisher = publisher;
        this.kafkaEnabled = kafkaEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBudgetChanged(BudgetChangedEvent event) {
        // Off in broker-less environments (e.g. the MySQL-only integration tests), so the
        // request never blocks on producer metadata. Defaults on for compose/prod.
        if (!kafkaEnabled) {
            return;
        }
        publisher.publish(event);
    }
}
