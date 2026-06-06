package com.pm.dashboardservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

/** user-service profile (returned raw, not wrapped in an envelope). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserProfileDto(
        Long userId,
        String fullName,
        String phone,
        LocalDate dateOfBirth,
        String avatarUrl,
        String occupation,
        String bio) {
}
