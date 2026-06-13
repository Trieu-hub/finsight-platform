package com.pm.budgetservice.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the AFTER_COMMIT bridge: it forwards to Kafka only when publishing is enabled.
 * (The {@code AFTER_COMMIT} timing itself is a Spring guarantee, exercised end-to-end at runtime.)
 */
class BudgetEventListenerTest {

    private final BudgetEventPublisher publisher = mock(BudgetEventPublisher.class);

    @Test
    void publishesWhenKafkaEnabled() {
        BudgetEventListener listener = new BudgetEventListener(publisher, true);
        BudgetChangedEvent event = sample();

        listener.onBudgetChanged(event);

        verify(publisher).publish(event);
    }

    @Test
    void skipsPublishWhenKafkaDisabled() {
        BudgetEventListener listener = new BudgetEventListener(publisher, false);

        listener.onBudgetChanged(sample());

        verify(publisher, never()).publish(any());
    }

    private BudgetChangedEvent sample() {
        return new BudgetChangedEvent(
                UUID.randomUUID(), "BudgetChanged", "2026-06-01T00:00:00Z",
                UUID.randomUUID(), 42L, 7L, "USD", new BigDecimal("1000"),
                "2026-06-01", "2026-06-30", "MONTHLY", false);
    }
}
