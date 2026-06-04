package com.pm.authservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Redis-backed refresh-token lifecycle: issue, rotate, revoke. */
class RefreshTokenIntegrationTest extends AbstractMockMvcIntegrationTest {

    private JsonNode registerAndLogin() throws Exception {
        long id = uniqueId();
        String email = "refresh" + id + "@finsight.test";
        register("user" + id, email, "password123");
        return login(email, "password123");
    }

    @Test
    void refreshReturnsNewTokens() throws Exception {
        String refreshToken = registerAndLogin().path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void rotationInvalidatesPreviousRefreshToken() throws Exception {
        String firstToken = registerAndLogin().path("refreshToken").asText();

        // Rotate: this issues a new token and revokes the old one.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(firstToken)))
                .andExpect(status().isOk());

        // Re-using the now-rotated token must fail.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(firstToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String refreshToken = registerAndLogin().path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshWithUnknownTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody("00000000-0000-0000-0000-000000000000")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }
}
