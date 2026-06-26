package com.pm.notificationservice.service.impl;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.ProcessedEvent;
import com.pm.notificationservice.event.RiskDetectedEvent;
import com.pm.notificationservice.exception.NotificationNotFoundException;
import com.pm.notificationservice.narrator.AlertContent;
import com.pm.notificationservice.narrator.AlertNarrator;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.repository.ProcessedEventRepository;
import com.pm.notificationservice.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final AlertNarrator narrator;

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   AlertNarrator narrator) {
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
        this.narrator = narrator;
    }

    /**
     * Idempotent create: the inbox check and both inserts run in one transaction, so a
     * redelivered event (same eventId) never produces a second notification. The PK on
     * {@code processed_events} is the final backstop against a concurrent double-insert.
     */
    @Override
    @Transactional
    public boolean createFromEvent(RiskDetectedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            return false;
        }

        AlertContent content = narrator.narrate(event);
        LocalDateTime now = LocalDateTime.now();

        Notification notification = Notification.builder()
                .id(UUID.randomUUID())
                .userId(event.userId())
                .type(content.type())
                .severity(event.riskSeverity())
                .title(content.title())
                .message(content.message())
                .sourceEventId(event.eventId())
                .read(false)
                .createdAt(now)
                .build();
        notificationRepository.save(notification);

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .processedAt(now)
                .build());
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Notification> list(Long userId, boolean unreadOnly, Pageable pageable) {
        return unreadOnly
                ? notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Override
    @Transactional
    public Notification markRead(Long userId, UUID id) {
        Notification notification = notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotificationNotFoundException(
                        "Notification " + id + " was not found"));
        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(LocalDateTime.now());
        }
        return notification;
    }

    @Override
    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId, LocalDateTime.now());
    }
}
