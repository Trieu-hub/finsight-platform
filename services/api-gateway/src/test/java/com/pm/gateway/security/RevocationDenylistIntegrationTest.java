package com.pm.gateway.security;

import com.pm.gateway.support.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The revocation denylist end to end inside the gateway: real Redis, real
 * {@link TokenRevocationChecker}, real filter path — nothing mocked.
 * <p>
 * The rest of the gateway's tests stub the checker out, which leaves two things unproven that
 * only fail in production: that Redis autoconfiguration really yields the
 * {@code StringRedisTemplate} the checker needs, and that the key this service *reads* is the
 * key auth-service *writes*. There is no shared library between them — the two agree on
 * {@code revoked:user:{userId}} by convention — so the key is written here by hand, exactly
 * as auth-service's {@code TokenRevocationService} writes it, and matched against
 * auth-service's own {@code TokenRevocationIntegrationTest}.
 * <p>
 * The route points at a dead port, so a token that survives the edge fails with 503: a
 * revoked token returning 401 and a live token returning 503 is what proves the denylist is
 * the thing making the difference.
 */
@SpringBootTest(properties = {
        "gateway.routes[0].prefix=/api/v1/budgets",
        "gateway.routes[0].uri=http://localhost:59999",
        "gateway.timeouts.connect-ms=500",
        "gateway.timeouts.read-ms=500"
})
@AutoConfigureMockMvc
class RevocationDenylistIntegrationTest {

    private static final String ISS = "finsight-auth";
    private static final String AUD = "finsight-api";
    /** The userId JwtTestTokens mints into every token; the denylist key must match it. */
    private static final long USER_ID = 1L;

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JwtTestTokens::publicKeyBase64);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    /** Autowired, not constructed: this is also the assertion that the bean exists at all. */
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clean() {
        redisTemplate.delete("revoked:user:" + USER_ID);
    }

    /** Writes the cutoff exactly as auth-service's TokenRevocationService does. */
    private void revokeUser() {
        long cutoffSeconds = System.currentTimeMillis() / 1000 + 1;
        redisTemplate.opsForValue().set(
                "revoked:user:" + USER_ID, String.valueOf(cutoffSeconds), Duration.ofMinutes(15));
    }

    @Test
    void tokenIsAcceptedWhileNoCutoffExists() throws Exception {
        mockMvc.perform(get("/api/v1/budgets")
                        .header("Authorization", "Bearer " + JwtTestTokens.valid(ISS, AUD)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void sameTokenIsRejectedOnceTheUserIsRevoked() throws Exception {
        String token = JwtTestTokens.valid(ISS, AUD);
        revokeUser();

        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOKEN_REVOKED"));
    }

    @Test
    void aTokenMintedAfterTheRevocationWorksAgain() throws Exception {
        revokeUser();
        // Re-login after logout: the cutoff is still there, but the new token post-dates it and
        // must be honoured. Waiting out the cutoff second is the point — the writer rounds up,
        // so a token minted within that second is intentionally still caught.
        Thread.sleep(1100);

        mockMvc.perform(get("/api/v1/budgets")
                        .header("Authorization", "Bearer " + JwtTestTokens.valid(ISS, AUD)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }
}
