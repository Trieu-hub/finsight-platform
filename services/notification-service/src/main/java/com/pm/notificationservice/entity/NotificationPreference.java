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

import java.time.LocalDateTime;

/**
 * How one user wants to be reached. See {@code V3__create_notification_preferences.sql} for why
 * the email address is stored here rather than fetched from the service that owns it.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    @Column(name = "user_id", updatable = false, nullable = false)
    private Long userId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
