package com.pm.notificationservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** Where an alert is POSTed, if the user pointed us at something. HTTPS only — see the validator. */
    @Column(name = "webhook_url", length = 2048)
    private String webhookUrl;

    @Column(name = "webhook_enabled", nullable = false)
    private boolean webhookEnabled;

    /**
     * HMAC key for the signature header. Held in the clear because signing needs the raw key; it is
     * shown to the user once, when it is minted, and never read back out over the API.
     */
    @Column(name = "webhook_secret", length = 64)
    private String webhookSecret;

    @Enumerated(EnumType.STRING)
    @Column(name = "digest_mode", nullable = false, length = 16)
    @Builder.Default
    private DigestMode digestMode = DigestMode.IMMEDIATE;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
