package com.pm.notificationservice.dto;

import com.pm.notificationservice.entity.DigestMode;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** How often the content-carrying channels should fire for this user. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DigestPreferenceRequest {

    @NotNull(message = "digestMode is required")
    private DigestMode digestMode;
}
