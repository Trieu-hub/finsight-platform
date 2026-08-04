package com.pm.notificationservice.webhook;

import com.pm.notificationservice.entity.Notification;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The JSON body of a webhook call.
 *
 * <p>Always an array, even for a single alert. A receiver writes one parser and it keeps working
 * when the user later switches to an hourly digest — the alternative, a bare alert object for one
 * and a wrapper for many, quietly breaks every integration the first time two alerts land in the
 * same window.
 *
 * <p>Timestamps are local date-times without a zone, matching what the REST API already returns
 * for the same rows; inventing a zone here would make the two disagree.
 */
public record WebhookPayload(LocalDateTime deliveredAt, int count, List<Alert> alerts) {

    public static WebhookPayload of(List<Notification> batch, LocalDateTime deliveredAt) {
        return new WebhookPayload(deliveredAt, batch.size(), batch.stream().map(Alert::of).toList());
    }

    public record Alert(String id,
                        String type,
                        String severity,
                        String title,
                        String message,
                        LocalDateTime createdAt) {

        static Alert of(Notification notification) {
            return new Alert(
                    notification.getId().toString(),
                    notification.getType(),
                    notification.getSeverity(),
                    notification.getTitle(),
                    notification.getMessage(),
                    notification.getCreatedAt());
        }
    }
}
