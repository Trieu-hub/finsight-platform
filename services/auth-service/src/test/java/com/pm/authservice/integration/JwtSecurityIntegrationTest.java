package com.pm.authservice.integration;

import com.pm.authservice.integration.support.JwtTestTokens;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JWT filter guarding the protected /me endpoint: accept valid, reject the rest. */
class JwtSecurityIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void meWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidTokenReturnsEmail() throws Exception {
        long id = uniqueId();
        String email = "me" + id + "@finsight.test";
        register("user" + id, email, "trailhead lantern 88");
        String accessToken = login(email, "trailhead lantern 88").path("accessToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().string(email));
    }

    @Test
    void meWithForgedSignatureIsUnauthorized() throws Exception {
        String forged = JwtTestTokens.forgedSignature(1L, "a@b.c", "ROLE_USER");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithExpiredTokenIsUnauthorized() throws Exception {
        String expired = JwtTestTokens.expired(1L, "a@b.c", "ROLE_USER");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }
}
