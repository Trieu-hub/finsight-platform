package com.pm.notificationservice.event;

import com.pm.notificationservice.service.NotificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumer filtering and counter accounting for the monthly report, verified without a broker by
 * invoking the listener directly. Same contract as the other two consumers: every event lands in
 * exactly one of created / duplicate / ignored.
 */
class MonthlyReportConsumerTest {

    private NotificationService service;
    private SimpleMeterRegistry registry;
    private MonthlyReportConsumer consumer;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        registry = new SimpleMeterRegistry();
        consumer = new MonthlyReportConsumer(service, registry);
    }

    @Test
    void createsNotificationAndCountsCreated() {
        when(service.createFromMonthlyReport(any())).thenReturn(true);

        consumer.onMonthlyReport(event(UUID.randomUUID(), 42L));

        verify(service).createFromMonthlyReport(any());
        assertThat(registry.counter("finsight.notifications.created").count()).isEqualTo(1.0);
    }

    @Test
    void countsDuplicateWhenInboxHit() {
        when(service.createFromMonthlyReport(any())).thenReturn(false);

        consumer.onMonthlyReport(event(UUID.randomUUID(), 42L));

        assertThat(registry.counter("finsight.notifications.duplicate").count()).isEqualTo(1.0);
    }

    @Test
    void ignoresEventWithoutEventId() {
        consumer.onMonthlyReport(event(null, 42L));

        verify(service, never()).createFromMonthlyReport(any());
        assertThat(registry.counter("finsight.notifications.ignored", "reason", "no_event_id").count())
                .isEqualTo(1.0);
    }

    @Test
    void ignoresEventWithoutUserId() {
        consumer.onMonthlyReport(event(UUID.randomUUID(), null));

        verify(service, never()).createFromMonthlyReport(any());
        assertThat(registry.counter("finsight.notifications.ignored", "reason", "no_user_id").count())
                .isEqualTo(1.0);
    }

    private static MonthlyReportEvent event(UUID eventId, Long userId) {
        return new MonthlyReportEvent(eventId, "MonthlyReportReady", "2026-08-01T03:20:00Z",
                userId, "2026-07", "VND", new BigDecimal("20000000"), new BigDecimal("12500000"),
                new BigDecimal("7500000"), 37.5, "Food", new BigDecimal("4000000"));
    }
}
