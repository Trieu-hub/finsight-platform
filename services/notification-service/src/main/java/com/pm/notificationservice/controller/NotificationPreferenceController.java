package com.pm.notificationservice.controller;

import com.pm.notificationservice.dto.ApiResponse;
import com.pm.notificationservice.dto.EmailPreferenceRequest;
import com.pm.notificationservice.dto.NotificationPreferenceResponse;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.security.JwtUserPrincipal;
import com.pm.notificationservice.service.NotificationPreferenceService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery preferences for the caller. The email address is never taken from the request — it is
 * read off the JWT, the same rule that governs userId.
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;
    private final ObjectProvider<JavaMailSender> mailSender;

    public NotificationPreferenceController(NotificationPreferenceService preferenceService,
                                            ObjectProvider<JavaMailSender> mailSender) {
        this.preferenceService = preferenceService;
        this.mailSender = mailSender;
    }

    @GetMapping
    public ApiResponse<NotificationPreferenceResponse> get(
            @AuthenticationPrincipal JwtUserPrincipal principal) {
        NotificationPreference preference = preferenceService.get(principal.getUserId());
        return ApiResponse.of(NotificationPreferenceResponse.from(preference, emailConfigured()));
    }

    @PutMapping
    public ApiResponse<NotificationPreferenceResponse> setEmail(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @Valid @RequestBody EmailPreferenceRequest request) {
        NotificationPreference preference = preferenceService.setEmailEnabled(
                principal.getUserId(), principal.getEmail(), request.isEmailEnabled());
        return ApiResponse.of(NotificationPreferenceResponse.from(preference, emailConfigured()));
    }

    private boolean emailConfigured() {
        return mailSender.getIfAvailable() != null;
    }
}
