package com.pm.notificationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a browser needs before it can subscribe. {@code enabled} is false when the server has no
 * VAPID keypair, which is the signal for the SPA to hide the control rather than ask for a
 * notification permission it could not honour.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PushConfigResponse {

    private boolean enabled;

    private String publicKey;
}
