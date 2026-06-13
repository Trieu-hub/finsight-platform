package com.pm.budgetservice.event;

/**
 * Outbound port for budget domain events. An interface so the service layer stays unaware
 * of Kafka and tests can swap the transport. Today there is a single Kafka-backed impl.
 */
public interface BudgetEventPublisher {

    /** Publishes a {@link BudgetChangedEvent} to the event backbone. */
    void publish(BudgetChangedEvent event);
}
