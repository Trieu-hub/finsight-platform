package com.pm.notificationservice.service;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.event.RiskDetectedEvent;
import com.pm.notificationservice.exception.NotificationNotFoundException;
import com.pm.notificationservice.narrator.TemplateNarrator;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.repository.ProcessedEventRepository;
import com.pm.notificationservice.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

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
        service = new NotificationServiceImpl(
                notificationRepository, processedEventRepository, new TemplateNarrator(), txManager);
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
