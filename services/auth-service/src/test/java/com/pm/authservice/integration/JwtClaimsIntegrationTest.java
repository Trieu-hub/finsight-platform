package com.pm.authservice.integration;

import com.pm.authservice.integration.support.JwtTestTokens;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Non-enforced introduction of issuer/audience: issued tokens carry them, but tokens
 * lacking them are still accepted (enforcement is deferred to a future gateway).
 */
class JwtClaimsIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void issuedTokenCarriesIssuerAndAudience() throws Exception {
        long id = uniqueId();
        String email = "claims" + id + "@finsight.test";
        register("user" + id, email, "password123");
        String accessToken = login(email, "password123").path("accessToken").asText();

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(accessToken).getPayload();

        assertEquals("finsight-auth", claims.getIssuer());
        assertTrue(claims.getAudience().contains("finsight-api"),
                "issued token should carry the configured audience");
    }

    @Test
    void tokenWithoutIssuerOrAudienceIsStillAccepted() throws Exception {
        long id = uniqueId();
        String email = "noaud" + id + "@finsight.test";
        register("user" + id, email, "password123");

        // Correctly signed and addressed to an existing user, but with no iss/aud claims.
        // Because validation does not yet enforce them, the request must still succeed.
        String token = JwtTestTokens.valid(jwtSecret, id, email, "ROLE_USER");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
