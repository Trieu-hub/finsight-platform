package com.pm.notificationservice.dto;

import com.pm.notificationservice.entity.Notification;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class NotificationResponse {

    private final UUID id;
    private final String type;
    private final String severity;
    private final String title;
    private final String message;
    private final boolean read;
    private final LocalDateTime createdAt;
    private final LocalDateTime readAt;

    public static NotificationResponse from(Notification n) {
        return NotificationResponse.builder()
                .id(n.getId())
                .type(n.getType())
                .severity(n.getSeverity())
                .title(n.getTitle())
                .message(n.getMessage())
                .read(n.isRead())
                .createdAt(n.getCreatedAt())
                .readAt(n.getReadAt())
                .build();
    }
}
