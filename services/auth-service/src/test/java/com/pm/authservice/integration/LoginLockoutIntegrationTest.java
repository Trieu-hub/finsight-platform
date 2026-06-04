package com.pm.authservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-account lockout. Test profile sets max-attempts = 3 (see application-test.yml),
 * so the 4th attempt is blocked regardless of credential correctness.
 */
class LoginLockoutIntegrationTest extends AbstractMockMvcIntegrationTest {

    private void attemptLogin(String email, String password, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void lockoutAfterMaxFailedAttemptsBlocksEvenCorrectPassword() throws Exception {
        long id = uniqueId();
        String email = "lockout" + id + "@finsight.test";
        register("user" + id, email, "password123");

        // Three failures (max-attempts = 3) — each still a normal 401.
        attemptLogin(email, "wrong", 401);
        attemptLogin(email, "wrong", 401);
        attemptLogin(email, "wrong", 401);

        // Now locked: even the correct password is refused with 429 ACCOUNT_LOCKED.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        long id = uniqueId();
        String email = "reset" + id + "@finsight.test";
        register("user" + id, email, "password123");

        // Two failures (below the threshold of 3)...
        attemptLogin(email, "wrong", 401);
        attemptLogin(email, "wrong", 401);

        // ...a success resets the counter...
        attemptLogin(email, "password123", 200);

        // ...so two more failures still do not lock (counter restarted).
        attemptLogin(email, "wrong", 401);
        attemptLogin(email, "wrong", 401);
        attemptLogin(email, "password123", 200);
    }
}
