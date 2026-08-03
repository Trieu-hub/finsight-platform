package com.pm.notificationservice.service;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.event.BudgetExceededEvent;
import com.pm.notificationservice.event.RiskDetectedEvent;
import com.pm.notificationservice.exception.NotificationNotFoundException;
import com.pm.notificationservice.narrator.TemplateNarrator;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.repository.ProcessedEventRepository;
import com.pm.notificationservice.service.impl.NotificationServiceImpl;
import com.pm.notificationservice.stream.NotificationStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceImplTest {

    private NotificationRepository notificationRepository;
    private ProcessedEventRepository processedEventRepository;
    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notificationRepository = mock(NotificationRepository.class);
        processedEventRepository = mock(ProcessedEventRepository.class);
        // A mock tx manager makes TransactionTemplate run the callback inline (getTransaction
        // returns a null status, commit is a no-op) — enough to exercise the persist path.
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        // Real narrator: deterministic, no need to mock.
        // The SSE stream is mocked: publishing to live browsers is a side channel, and this test
        // is about what gets persisted.
        // No delivery channels: this test is about what gets persisted, and the channels are a
        // best-effort side path that runs after the commit.
        service = new NotificationServiceImpl(
                notificationRepository, processedEventRepository, new TemplateNarrator(),
                mock(NotificationStream.class), List.of(), txManager);
    }

    @Test
    void createFromEventPersistsNotificationAndInboxRowForNewEvent() {
        RiskDetectedEvent event = event(UUID.randomUUID());
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        boolean created = service.createFromEvent(event);

        assertThat(created).isTrue();
        verify(notificationRepository).save(any(Notification.class));
        verify(processedEventRepository).save(any());
    }

    @Test
    void createFromEventSkipsWhenAlreadyProcessed() {
        RiskDetectedEvent event = event(UUID.randomUUID());
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        boolean created = service.createFromEvent(event);

        assertThat(created).isFalse();
        verify(notificationRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    // --- BudgetExceeded -------------------------------------------------------------------
    // Same inbox, same persistence, same after-commit fan-out as a risk alert; only the wording
    // and the source event differ.

    @Test
    void createFromBudgetExceededSpellsOutTheOverspendUsingBudgetServicesOwnFigures() {
        BudgetExceededEvent event = budgetEvent(UUID.randomUUID(), "1000000", "1250000");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        boolean created = service.createFromBudgetExceeded(event);

        assertThat(created).isTrue();
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();

        assertThat(saved.getType()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(saved.getSeverity()).isEqualTo("HIGH");
        assertThat(saved.getTitle()).isEqualTo("Budget exceeded");
        // Grouped and locale-independent, and it states the overshoot rather than making the
        // reader subtract two numbers.
        assertThat(saved.getMessage())
                .contains("1,250,000 VND")
                .contains("1,000,000 VND")
                .contains("250,000 VND over the limit");
        assertThat(saved.getSourceEventId()).isEqualTo(event.eventId());
        verify(processedEventRepository).save(any());
    }

    @Test
    void createFromBudgetExceededIsGuardedByTheSameInbox() {
        BudgetExceededEvent event = budgetEvent(UUID.randomUUID(), "1000000", "1250000");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        assertThat(service.createFromBudgetExceeded(event)).isFalse();
        verify(notificationRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    private static BudgetExceededEvent budgetEvent(UUID eventId, String limit, String spent) {
        return new BudgetExceededEvent(eventId, "BudgetExceeded", "2026-08-03T00:00:00Z",
                UUID.randomUUID(), 42L, 4L, "VND",
                new java.math.BigDecimal(limit), new java.math.BigDecimal(spent));
    }

    @Test
    void markReadThrowsWhenNotFoundForUser() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findByIdAndUserId(id, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(42L, id))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void markReadSetsReadFlagWhenFound() {
        UUID id = UUID.randomUUID();
        Notification n = Notification.builder().id(id).userId(42L).read(false).build();
        when(notificationRepository.findByIdAndUserId(id, 42L)).thenReturn(Optional.of(n));

        Notification result = service.markRead(42L, id);

        assertThat(result.isRead()).isTrue();
        assertThat(result.getReadAt()).isNotNull();
    }

    private RiskDetectedEvent event(UUID eventId) {
        return new RiskDetectedEvent(eventId, "RiskDetected", "2026-06-26T10:00:00Z",
                42L, UUID.randomUUID(), "HIGH_AMOUNT_EXPENSE", "HIGH");
    }
}
