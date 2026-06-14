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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT security integration test for user-service (TC-2), mirroring transaction/budget's
 * {@code *ApiSecurityIntegrationTest}: exercises the real filter chain against a MySQL container —
 * a missing/forged/expired token is rejected, a valid token is accepted and the profile is scoped
 * to the token's {@code userId} (never the request body/URL), and one user cannot see another's.
 */
@AutoConfigureMockMvc
class UserProfileApiSecurityIntegrationTest extends AbstractMySqlIntegrationTest {

    /** A different 64-byte secret — signatures made with it must not validate. */
    private static final String WRONG_SECRET =
            "wrong-secret-wrong-secret-wrong-secret-wrong-secret-0123456789ab";

    private static final AtomicLong USER_SEQUENCE = new AtomicLong(300_000L);

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
    void rejectsRequestWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsJwtWithInvalidSignature() throws Exception {
        String forged = JwtTestTokens.valid(WRONG_SECRET, 1L, "a@b.c", "ROLE_USER");
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredJwt() throws Exception {
        String expired = JwtTestTokens.expired(jwtSecret, 1L, "a@b.c", "ROLE_USER");
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsValidJwtAndScopesProfileToTokenUserId() throws Exception {
        long userId = uniqueUserId();
        // No userId in the body — identity comes from the token.
        mockMvc.perform(post("/api/v1/users/me")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Auth Scoped"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId));

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.fullName").value("Auth Scoped"));
    }

    @Test
    void userCannotSeeAnotherUsersProfile() throws Exception {
        long userA = uniqueUserId();
        long userB = uniqueUserId();
        mockMvc.perform(post("/api/v1/users/me")
                        .header("Authorization", bearer(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"User A"}
                                """))
                .andExpect(status().isCreated());

        // User B reads /me with their own token — they have no profile, so 404 (A's is not leaked).
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(userB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
