package com.pm.notificationservice.service;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.event.RiskDetectedEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationService {

    /**
     * Creates a notification from a detected-risk event, idempotently.
     *
     * @return {@code true} if a new notification was created, {@code false} if the event
     *         had already been processed (idempotency inbox hit)
     */
    boolean createFromEvent(RiskDetectedEvent event);

    /** A page of the user's notifications, newest first; optionally unread-only. */
    Page<Notification> list(Long userId, boolean unreadOnly, Pageable pageable);

    long unreadCount(Long userId);

    /** Marks one notification read; throws if it does not belong to the user. */
    Notification markRead(Long userId, UUID id);

    /** Marks all of the user's unread notifications read; returns the count affected. */
    int markAllRead(Long userId);
}
