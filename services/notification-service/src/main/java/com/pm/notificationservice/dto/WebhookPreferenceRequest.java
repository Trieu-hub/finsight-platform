package com.pm.notificationservice.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Where to POST this user's alerts.
 *
 * <p>A null url clears the webhook and its secret. The shape of the URL is checked here only for
 * length — whether it is one we are willing to call is
 * {@link com.pm.notificationservice.webhook.WebhookUrlValidator}'s decision, and that is a security
 * rule rather than a validation annotation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookPreferenceRequest {

    @Size(max = 2048, message = "Webhook URL must be at most 2048 characters")
    private String url;

    private boolean enabled;
}
