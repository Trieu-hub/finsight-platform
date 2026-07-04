package com.pm.dashboardservice.security;

import com.pm.dashboardservice.support.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JWT security integration test for dashboard-service (TC-2). Boots the full context and exercises
 * the real filter chain on the secured composite endpoint: a missing, forged or expired token is
 * rejected with 401, while a valid token passes authentication and proceeds to the upstream fan-out
 * (which fails fast against the unreachable test URIs → 502, proving the request got past security).
 *
 * <p>Upstream URIs point at a closed port so the authenticated path fails immediately with a
 * connection refusal rather than a slow timeout. The RS256 public key is supplied via
 * {@code @DynamicPropertySource} from {@link JwtTestTokens}, whose private key mints the tokens.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dashboard.services.user-uri=http://localhost:1",
        "dashboard.services.transaction-uri=http://localhost:1",
        "dashboard.services.budget-uri=http://localhost:1"
})
class DashboardApiSecurityIntegrationTest {

    private static final String ISSUER = "finsight-auth";
    private static final String AUDIENCE = "finsight-api";

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JwtTestTokens::publicKeyBase64);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsRequestWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsJwtWithInvalidSignature() throws Exception {
        String forged = JwtTestTokens.forgedSignature(ISSUER, AUDIENCE);
        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredJwt() throws Exception {
        String expired = JwtTestTokens.expired(ISSUER, AUDIENCE);
        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validJwtPassesSecurityAndReachesUpstreamFanOut() throws Exception {
        // Authenticated: not 401. The unreachable upstreams then surface as 502 (UpstreamException).
        String valid = JwtTestTokens.valid(ISSUER, AUDIENCE);
        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + valid))
                .andExpect(status().isBadGateway());
    }
}
