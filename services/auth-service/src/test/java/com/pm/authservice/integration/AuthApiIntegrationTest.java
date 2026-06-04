package com.pm.authservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration + login: happy paths, duplicates, validation, malformed input. */
class AuthApiIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void registerThenLoginReturnsTokens() throws Exception {
        long id = uniqueId();
        String email = "user" + id + "@finsight.test";
        register("user" + id, email, "password123");

        JsonNode body = login(email, "password123");
        org.junit.jupiter.api.Assertions.assertTrue(body.path("success").asBoolean());
        org.junit.jupiter.api.Assertions.assertFalse(body.path("accessToken").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertFalse(body.path("refreshToken").asText().isBlank());
    }

    @Test
    void registerDuplicateEmailIsConflict() throws Exception {
        long id = uniqueId();
        String email = "dupe" + id + "@finsight.test";
        register("first" + id, email, "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("second" + id, email, "password123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void registerDuplicateUsernameIsConflict() throws Exception {
        long id = uniqueId();
        String username = "name" + id;
        register(username, "u" + id + "@finsight.test", "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, "other" + id + "@finsight.test", "password123")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    void registerInvalidEmailIsValidationError() throws Exception {
        long id = uniqueId();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("user" + id, "not-an-email", "password123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void registerShortPasswordIsValidationError() throws Exception {
        long id = uniqueId();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("user" + id, "user" + id + "@finsight.test", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void loginWrongPasswordIsUnauthorized() throws Exception {
        long id = uniqueId();
        String email = "wrongpw" + id + "@finsight.test";
        register("user" + id, email, "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void loginUnknownEmailIsUnauthorized() throws Exception {
        long id = uniqueId();
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost" + id + "@finsight.test\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.error.message", not(emptyOrNullString())));
    }
}
