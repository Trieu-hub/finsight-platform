package com.pm.notificationservice.dto;

import com.pm.notificationservice.entity.NotificationPreference;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the SPA needs to render the delivery toggles. {@code emailConfigured} says whether the
 * server can send mail at all, so the UI can hide a switch that would do nothing — the same
 * courtesy the push control gets.
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

    public static NotificationPreferenceResponse from(NotificationPreference preference, boolean configured) {
        return new NotificationPreferenceResponse(
                preference.isEmailEnabled(), preference.getEmail(), configured);
    }
}
