package com.pm.userservice.integration;

import com.pm.userservice.integration.support.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP integration test against a real MySQL 8 container. Exercises the
 * genuine security filter chain (real JWT validation — nothing mocked), the service
 * layer, the Spring Data repository, the Flyway-owned schema, and Hibernate
 * {@code ddl-auto: validate} — the same strategy the other FinSight services use.
 */
@AutoConfigureMockMvc
class UserProfileApiIntegrationTest extends AbstractMySqlIntegrationTest {

    /** Distinct per test so the shared container never leaks data between tests. */
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(100_000L);

    @Autowired
    private MockMvc mockMvc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private long uniqueUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    private String bearer(long userId) {
        return "Bearer " + JwtTestTokens.valid(
                jwtSecret, userId, "user" + userId + "@finsight.test", "ROLE_USER");
    }

    @Test
    void createThenGetProfile_persistsToMySql() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"fullName":"Nguyen Van A","phone":"+84901234567","occupation":"Engineer","bio":"Hello"}
                """;

        mockMvc.perform(post("/api/v1/users/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.fullName").value("Nguyen Van A"));

        // Read back from the database (fresh request, real row).
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.phone").value("+84901234567"))
                .andExpect(jsonPath("$.bio").value("Hello"))
                // JPA auditing populated the DATETIME(6) columns on insert.
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void updateProfile_partialUpdate_keepsOtherFields() throws Exception {
        long userId = uniqueUserId();
        mockMvc.perform(post("/api/v1/users/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Original Name","occupation":"Engineer"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bio":"Updated bio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Updated bio"))
                .andExpect(jsonPath("$.fullName").value("Original Name"))   // unchanged
                .andExpect(jsonPath("$.occupation").value("Engineer"));     // unchanged
    }

    @Test
    void createDuplicateProfile_returns409() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"fullName":"Dup User"}
                """;
        mockMvc.perform(post("/api/v1/users/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getMissingProfile_returns404() throws Exception {
        long userId = uniqueUserId();
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void request_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
