package com.pm.notificationservice.integration;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.integration.support.JwtTestTokens;
import com.pm.notificationservice.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security boundary: no token / bad token is rejected, and a user can never reach
 * another user's notifications (queries are userId-scoped, so a foreign id reads as
 * "not found").
 */
class NotificationApiSecurityIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Autowired
    private NotificationRepository repository;

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        long userId = uniqueUserId();
        String expired = JwtTestTokens.expired(jwtSecret, userId, "u" + userId + "@finsight.test", "USER");
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cannotMarkAnotherUsersNotificationRead() throws Exception {
        long owner = uniqueUserId();
        long attacker = uniqueUserId();
        Notification n = repository.save(Notification.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .type("RISK_ALERT")
                .severity("HIGH")
                .title("Owner only")
                .message("secret")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build());

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", n.getId())
                        .header("Authorization", bearer(attacker)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
    }
}
