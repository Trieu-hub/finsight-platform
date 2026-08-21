package com.pm.authservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pm.authservice.integration.support.JwtTestTokens;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The write side of access-token revocation, against real Redis.
 * <p>
 * Enforcement lives in api-gateway (it alone reads this key), so what auth-service owes the
 * contract is exactly this: on logout, a cutoff exists for the user, and it is late enough to
 * cover the access token that was just handed out. Those two properties are what these tests
 * pin down — the key name and unit are the contract api-gateway's
 * {@code TokenRevocationChecker} reads back.
 */
class TokenRevocationIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private JsonNode registerAndLogin() throws Exception {
        long id = uniqueId();
        String email = "revoke" + id + "@finsight.test";
        register("user" + id, email, "trailhead lantern 88");
        return login(email, "trailhead lantern 88");
    }

    /** Reads the claims of a token this service just minted, using the test keypair. */
    private static Claims claimsOf(String accessToken) throws Exception {
        byte[] der = Base64.getDecoder().decode(JwtTestTokens.publicKeyBase64());
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
        return Jwts.parser().verifyWith(publicKey).build()
                .parseSignedClaims(accessToken).getPayload();
    }

    private String cutoffFor(Object userId) {
        return redisTemplate.opsForValue().get("revoked:user:" + userId);
    }

    @Test
    void loginAloneRevokesNothing() throws Exception {
        Claims claims = claimsOf(registerAndLogin().path("accessToken").asText());

        // Guards the subtle failure mode: issue() revokes the *refresh* token on every login,
        // so wiring access-token revocation into that path would have made every fresh login
        // instantly self-revoking.
        assertThat(cutoffFor(claims.get("userId"))).isNull();
    }

    @Test
    void logoutWritesACutoffThatCoversTheAccessTokenJustIssued() throws Exception {
        JsonNode session = registerAndLogin();
        Claims claims = claimsOf(session.path("accessToken").asText());
        Object userId = claims.get("userId");
        assertThat(cutoffFor(userId)).isNull();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(session.path("refreshToken").asText())))
                .andExpect(status().isOk());

        String cutoff = cutoffFor(userId);
        assertThat(cutoff).as("logout must record a revocation cutoff").isNotNull();

        // The cutoff must be strictly greater than the token's iat, or the gateway's
        // "iat < cutoff" test would let the just-logged-out token keep working. Login and
        // logout happen within the same second here, which is exactly the case the writer's
        // round-up is there to cover — so this asserts that round-up really happens.
        long issuedAtSeconds = claims.getIssuedAt().getTime() / 1000;
        assertThat(Long.parseLong(cutoff)).isGreaterThan(issuedAtSeconds);
    }

    @Test
    void cutoffExpiresWithTheAccessTokenLifetime() throws Exception {
        JsonNode session = registerAndLogin();
        Object userId = claimsOf(session.path("accessToken").asText()).get("userId");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(session.path("refreshToken").asText())))
                .andExpect(status().isOk());

        // Bounded by the access-token TTL: once every token predating the cutoff has expired
        // on its own, the entry has nothing left to say and Redis drops it — no cleanup job.
        Long ttl = redisTemplate.getExpire("revoked:user:" + userId);
        assertThat(ttl).isNotNull().isPositive();
        assertThat(ttl).isLessThanOrEqualTo(900); // jwt.access-token-expiration = 15 min
    }
}
