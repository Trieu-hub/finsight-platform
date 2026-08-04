package com.pm.notificationservice.dto;

import com.pm.notificationservice.entity.DigestMode;
import com.pm.notificationservice.entity.NotificationPreference;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the SPA needs to render the delivery controls. {@code emailConfigured} says whether the
 * server can send mail at all, so the UI can hide a switch that would do nothing — the same
 * courtesy the push control gets.
 *
 * <p>{@code webhookSecret} is populated on exactly one response: the one that minted it. Every
 * later read leaves it null, because this service keeps the secret to sign with, not to hand back.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferenceResponse {

    private boolean emailEnabled;

    /** Echoed back so the user can see which address alerts would go to. */
    private String email;

    private boolean emailConfigured;

    private boolean webhookEnabled;

    private String webhookUrl;

    /** Non-null only in the response to the call that generated it. Never re-readable. */
    private String webhookSecret;

    private DigestMode digestMode;

    public static NotificationPreferenceResponse from(NotificationPreference preference, boolean configured) {
        return from(preference, configured, null);
    }

    public static NotificationPreferenceResponse from(NotificationPreference preference,
                                                      boolean configured,
                                                      String freshSecret) {
        return new NotificationPreferenceResponse(
                preference.isEmailEnabled(),
                preference.getEmail(),
                configured,
                preference.isWebhookEnabled(),
                preference.getWebhookUrl(),
                freshSecret,
                preference.getDigestMode());
    }
}
