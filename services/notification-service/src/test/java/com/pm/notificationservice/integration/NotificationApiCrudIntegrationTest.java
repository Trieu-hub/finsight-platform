package com.pm.notificationservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP coverage of the notification read/mark-read API against a real MySQL
 * container. Notifications are seeded through the repository (they originate from Kafka,
 * not an HTTP create), then exercised over the genuine security filter chain.
 */
class NotificationApiCrudIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Autowired
    private NotificationRepository repository;

    @Test
    void listsNotificationsNewestFirstAndReportsUnreadCount() throws Exception {
        long userId = uniqueUserId();
        seed(userId, "Older", LocalDateTime.now().minusHours(2));
        seed(userId, "Newer", LocalDateTime.now().minusHours(1));

        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].title").value("Newer"))
                .andExpect(jsonPath("$.data[1].title").value("Older"))
                .andExpect(jsonPath("$.meta.total").value(2));

        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    void markReadFlipsOneAndDecrementsUnreadCount() throws Exception {
        long userId = uniqueUserId();
        Notification n = seed(userId, "Alert", LocalDateTime.now());

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", n.getId())
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true));

        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(userId)))
                .andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void markAllReadClearsUnread() throws Exception {
        long userId = uniqueUserId();
        seed(userId, "A", LocalDateTime.now());
        seed(userId, "B", LocalDateTime.now());

        mockMvc.perform(patch("/api/v1/notifications/read-all").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));

        mockMvc.perform(get("/api/v1/notifications/unread-count").header("Authorization", bearer(userId)))
                .andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void unreadOnlyFilterExcludesReadNotifications() throws Exception {
        long userId = uniqueUserId();
        Notification read = seed(userId, "Read one", LocalDateTime.now().minusHours(1));
        read.setRead(true);
        repository.save(read);
        seed(userId, "Unread one", LocalDateTime.now());

        String body = mockMvc.perform(get("/api/v1/notifications?unreadOnly=true")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = asJson(body).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("title").asText()).isEqualTo("Unread one");
    }

    private Notification seed(long userId, String title, LocalDateTime createdAt) {
        return repository.save(Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .type("RISK_ALERT")
                .severity("HIGH")
                .title(title)
                .message("message for " + title)
                .sourceEventId(UUID.randomUUID())
                .read(false)
                .createdAt(createdAt)
                .build());
    }
}
