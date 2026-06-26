package com.pm.notificationservice.controller;

import com.pm.notificationservice.dto.ApiResponse;
import com.pm.notificationservice.dto.NotificationResponse;
import com.pm.notificationservice.dto.PageMeta;
import com.pm.notificationservice.dto.UnreadCountResponse;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.security.JwtUserPrincipal;
import com.pm.notificationservice.service.NotificationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * In-app notifications for the authenticated user. {@code userId} is read ONLY from the
 * JWT principal (never the request) so a caller can only ever see or mutate their own
 * notifications. Pagination is 1-based in the API, 0-based in Spring Data.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<List<NotificationResponse>> list(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {

        int safePage = Math.max(page, 1);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        if (limit <= 0) {
            safeLimit = DEFAULT_LIMIT;
        }

        Page<Notification> result = notificationService.list(
                principal.getUserId(), unreadOnly, PageRequest.of(safePage - 1, safeLimit));

        List<NotificationResponse> data = result.getContent().stream()
                .map(NotificationResponse::from)
                .toList();
        PageMeta meta = new PageMeta(safePage, safeLimit, result.getTotalElements());
        return ApiResponse.of(data, meta);
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(
            @AuthenticationPrincipal JwtUserPrincipal principal) {
        long count = notificationService.unreadCount(principal.getUserId());
        return ApiResponse.of(new UnreadCountResponse(count));
    }

    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationResponse> markRead(
            @AuthenticationPrincipal JwtUserPrincipal principal,
            @PathVariable UUID id) {
        Notification updated = notificationService.markRead(principal.getUserId(), id);
        return ApiResponse.of(NotificationResponse.from(updated));
    }

    @PatchMapping("/read-all")
    public ApiResponse<UnreadCountResponse> markAllRead(
            @AuthenticationPrincipal JwtUserPrincipal principal) {
        int affected = notificationService.markAllRead(principal.getUserId());
        return ApiResponse.of(new UnreadCountResponse(affected));
    }
}
