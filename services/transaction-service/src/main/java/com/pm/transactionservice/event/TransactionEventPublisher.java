package com.pm.transactionservice.event;

/**
 * Outbound port for transaction domain events. Keeping this an interface lets the
 * service layer stay unaware of Kafka and lets tests/alternative transports swap the
 * implementation. Today there is a single Kafka-backed implementation.
 */
public interface TransactionEventPublisher {

    /** Publishes a {@link TransactionCreatedEvent} to the event backbone. */
    void publish(TransactionCreatedEvent event);
}
