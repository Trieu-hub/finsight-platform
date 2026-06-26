package com.pm.notificationservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An in-app notification for a single user, materialized from an upstream event
 * (currently {@code RiskDetected}). The id is a {@link UUID} generated in app code,
 * matching the rest of FinSight's persistence convention.
 *
 * <p>Read state is mutable ({@code isRead}/{@code readAt}); everything else is set once
 * at creation by the consumer and never updated.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "type", nullable = false, length = 64)
    private String type;

    @Column(name = "severity", nullable = false, length = 32)
    private String severity;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "message", nullable = false, length = 512)
    private String message;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_event_id", length = 36)
    private UUID sourceEventId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;
}
