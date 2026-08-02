package com.pm.notificationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the browser's {@code PushSubscription.toJSON()} gives the SPA, flattened. There is no
 * userId here on purpose — it comes from the JWT, never from the body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushSubscriptionRequest {

    @NotBlank
    @Size(max = 512)
    private String endpoint;

    @NotBlank
    @Size(max = 255)
    private String p256dh;

    @NotBlank
    @Size(max = 255)
    private String auth;
}
