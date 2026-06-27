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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final AlertNarrator narrator;
    private final TransactionTemplate transactionTemplate;

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   AlertNarrator narrator,
                                   PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
        this.narrator = narrator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Idempotent create. Narration runs FIRST and OUTSIDE the database transaction: the narrator
     * may call an external LLM, and holding a DB connection open across a network call would tie
     * up the pool. The duplicate check also short-circuits before narration, so a redelivered
     * event never pays for a second LLM call. Only the two inserts run transactionally — with a
     * second inbox check inside the transaction (and the {@code processed_events} PK as the final
     * backstop) guarding against a concurrent double-insert.
     */
    @Override
    public boolean createFromEvent(RiskDetectedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            return false;
        }

        AlertContent content = narrator.narrate(event);

        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (processedEventRepository.existsById(event.eventId())) {
                return false;
            }
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
        }));
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
