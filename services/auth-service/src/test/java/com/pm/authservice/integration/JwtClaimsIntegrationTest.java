package com.pm.authservice.integration;

import com.pm.authservice.integration.support.JwtTestTokens;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issuer/audience are now ENFORCED (parity with the gateway and the other services):
 * issued tokens carry them, and tokens lacking them are rejected.
 */
class JwtClaimsIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void issuedTokenCarriesIssuerAndAudience() throws Exception {
        long id = uniqueId();
        String email = "claims" + id + "@finsight.test";
        register("user" + id, email, "password123");
        String accessToken = login(email, "password123").path("accessToken").asText();

        // The login token is RS256-signed with the test private key; verify it with the
        // matching public key (the same pair the app is configured with).
        Claims claims = Jwts.parser().verifyWith(JwtTestTokens.publicKey()).build()
                .parseSignedClaims(accessToken).getPayload();

        assertEquals("finsight-auth", claims.getIssuer());
        assertTrue(claims.getAudience().contains("finsight-api"),
                "issued token should carry the configured audience");
    }

    @Test
    void tokenWithoutIssuerOrAudienceIsRejected() throws Exception {
        long id = uniqueId();
        String email = "noaud" + id + "@finsight.test";
        register("user" + id, email, "password123");

        // Correctly signed and addressed to an existing user, but with no iss/aud claims.
        // Issuer/audience are now enforced, so the request must be rejected.
        String token = JwtTestTokens.valid(id, email, "ROLE_USER");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
