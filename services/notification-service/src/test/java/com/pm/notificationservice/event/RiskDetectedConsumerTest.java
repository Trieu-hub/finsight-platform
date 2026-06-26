package com.pm.notificationservice.event;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pm.notificationservice.service.NotificationService;

/**
 * Consumer filtering and counter accounting, verified without a broker by invoking the
 * listener method directly. Every event must land in exactly one of created / duplicate
 * / ignored.
 */
class RiskDetectedConsumerTest {

    private NotificationService service;
    private SimpleMeterRegistry registry;
    private RiskDetectedConsumer consumer;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        registry = new SimpleMeterRegistry();
        consumer = new RiskDetectedConsumer(service, registry);
    }

    @Test
    void createsNotificationAndCountsCreated() {
        when(service.createFromEvent(any())).thenReturn(true);

        consumer.onRiskDetected(event(UUID.randomUUID(), 42L));

        verify(service).createFromEvent(any());
        assertThat(registry.counter("finsight.notifications.created").count()).isEqualTo(1.0);
    }

    @Test
    void countsDuplicateWhenInboxHit() {
        when(service.createFromEvent(any())).thenReturn(false);

        consumer.onRiskDetected(event(UUID.randomUUID(), 42L));

        assertThat(registry.counter("finsight.notifications.duplicate").count()).isEqualTo(1.0);
    }

    @Test
    void ignoresEventWithoutEventId() {
        consumer.onRiskDetected(event(null, 42L));

        verify(service, never()).createFromEvent(any());
        assertThat(registry.counter("finsight.notifications.ignored", "reason", "no_event_id").count())
                .isEqualTo(1.0);
    }

    @Test
    void ignoresEventWithoutUserId() {
        consumer.onRiskDetected(event(UUID.randomUUID(), null));

        verify(service, never()).createFromEvent(any());
        assertThat(registry.counter("finsight.notifications.ignored", "reason", "no_user_id").count())
                .isEqualTo(1.0);
    }

    private RiskDetectedEvent event(UUID eventId, Long userId) {
        return new RiskDetectedEvent(eventId, "RiskDetected", "2026-06-26T10:00:00Z",
                userId, UUID.randomUUID(), "HIGH_AMOUNT_EXPENSE", "HIGH");
    }
}
