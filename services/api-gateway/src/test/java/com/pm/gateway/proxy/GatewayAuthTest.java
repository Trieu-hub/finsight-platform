package com.pm.gateway.proxy;

import com.pm.gateway.security.TokenRevocationChecker;
import com.pm.gateway.support.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 edge-authentication contract (docs/ADR-0002 §1-§5).
 *
 * <p>Both the protected route ({@code /api/v1/budgets}) and a public route
 * ({@code /api/v1/auth/login}) are pointed at a definitely-dead port. So a request that
 * <b>passes</b> authentication reaches the forward and fails with
 * {@code 503 SERVICE_UNAVAILABLE}, while a request <b>rejected</b> at the edge returns
 * its {@code 401} auth code and never reaches the forward. That difference is exactly
 * what proves the auth gate works — independent of whether real backends are running.
 */
@SpringBootTest(properties = {
        "gateway.routes[0].prefix=/api/v1/budgets",
        "gateway.routes[0].uri=http://localhost:59999",
        "gateway.routes[1].prefix=/api/v1/auth",
        "gateway.routes[1].uri=http://localhost:59999",
        "gateway.timeouts.connect-ms=500",
        "gateway.timeouts.read-ms=500"
})
@AutoConfigureMockMvc
class GatewayAuthTest {

    private static final String ISS = "finsight-auth";
    private static final String AUD = "finsight-api";

    /**
     * Stands in for the Redis-backed denylist, which has no server here. Mockito's default
     * {@code false} means "not revoked", so every other test in this class keeps exercising
     * the pre-existing outcomes untouched; {@link #revokedTokenIsRejected()} stubs it true.
     */
    @MockitoBean
    private TokenRevocationChecker revocationChecker;

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JwtTestTokens::publicKeyBase64);
    }

    @Autowired
    MockMvc mockMvc;

    // ── Rejected at the edge (401, never forwarded) ─────────────────────────────

    @Test
    void protectedRoute_noToken_returnsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/budgets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void protectedRoute_malformedAuthHeader_returnsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Token abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void protectedRoute_expiredToken_returnsTokenExpired() throws Exception {
        String token = JwtTestTokens.expired(ISS, AUD);
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void protectedRoute_badSignature_returnsTokenInvalid() throws Exception {
        String token = JwtTestTokens.badSignature(ISS, AUD);
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void protectedRoute_wrongAlgorithm_returnsTokenInvalid() throws Exception {
        String token = JwtTestTokens.wrongAlgorithm(ISS, AUD);
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void protectedRoute_wrongIssuer_returnsTokenInvalid() throws Exception {
        String token = JwtTestTokens.wrongIssuer(AUD);
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void protectedRoute_wrongAudience_returnsTokenInvalid() throws Exception {
        String token = JwtTestTokens.wrongAudience(ISS);
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void revokedTokenIsRejected() throws Exception {
        // A fully valid token — right signature, alg, issuer, audience, not expired — that
        // auth-service has since revoked (logout / ban / role change). It must be rejected at
        // the edge and never reach the forward, which is what separates this from
        // protectedRoute_validToken_passesAuthAndForwards below (same token, 503).
        when(revocationChecker.isRevoked(any())).thenReturn(true);

        String token = JwtTestTokens.valid(ISS, AUD);
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOKEN_REVOKED"));
    }

    // ── Passed the edge (reaches forward → 503 against the dead backend) ─────────

    @Test
    void protectedRoute_validToken_passesAuthAndForwards() throws Exception {
        String token = JwtTestTokens.valid(ISS, AUD);
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void publicRoute_noToken_bypassesAuthAndForwards() throws Exception {
        // /api/v1/auth/login is on the public allow-list: no token, yet it must NOT be
        // rejected — it reaches the (dead) backend and yields 503, not 401.
        mockMvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void authMe_isProtected_noToken_returnsUnauthenticated() throws Exception {
        // /api/v1/auth/me is NOT public (only register/login/refresh/logout are).
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
}
