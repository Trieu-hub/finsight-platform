package com.pm.notificationservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Unsubscribing needs the endpoint and nothing else — separate from
 * {@link PushSubscriptionRequest} so a caller is not made to send the subscription keys it is
 * about to throw away.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PushUnsubscribeRequest {

    @NotBlank
    @Size(max = 512)
    private String endpoint;
}
