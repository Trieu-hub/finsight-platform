package com.pm.dashboardservice.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT security integration test for dashboard-service (TC-2). Boots the full context and exercises
 * the real filter chain on the secured composite endpoint: a missing, forged or expired token is
 * rejected with 401, while a valid token passes authentication and proceeds to the upstream fan-out
 * (which fails fast against the unreachable test URIs → 502, proving the request got past security).
 *
 * <p>Upstream URIs point at a closed port so the authenticated path fails immediately with a
 * connection refusal rather than a slow timeout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789abcdef",
        "dashboard.services.user-uri=http://localhost:1",
        "dashboard.services.transaction-uri=http://localhost:1",
        "dashboard.services.budget-uri=http://localhost:1"
})
class DashboardApiSecurityIntegrationTest {

    private static final String SECRET =
            "test-secret-test-secret-test-secret-test-secret-0123456789abcdef";
    private static final String WRONG_SECRET =
            "wrong-secret-wrong-secret-wrong-secret-wrong-secret-0123456789ab";
    private static final String ISSUER = "finsight-auth";
    private static final String AUDIENCE = "finsight-api";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsRequestWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsJwtWithInvalidSignature() throws Exception {
        String forged = token(WRONG_SECRET, ISSUER, AUDIENCE, 3_600_000L);
        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredJwt() throws Exception {
        String expired = token(SECRET, ISSUER, AUDIENCE, -3_600_000L);
        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validJwtPassesSecurityAndReachesUpstreamFanOut() throws Exception {
        // Authenticated: not 401. The unreachable upstreams then surface as 502 (UpstreamException).
        String valid = token(SECRET, ISSUER, AUDIENCE, 3_600_000L);
        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + valid))
                .andExpect(status().isBadGateway());
    }

    private static String token(String secret, String issuer, String audience, long ttlMillis) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claim("userId", 1L)
                .claim("email", "a@b.c")
                .claim("role", "USER")
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }
}
