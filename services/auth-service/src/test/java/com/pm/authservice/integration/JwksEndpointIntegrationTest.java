package com.pm.authservice.integration;

import com.pm.authservice.integration.support.JwtTestTokens;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import org.junit.jupiter.api.Test;

import java.security.Key;
import java.security.PublicKey;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published JWK Set, checked the way a validator actually consumes it.
 *
 * <p>The contract between auth-service and the seven validators is a JSON document and a
 * {@code kid} — there is no shared library to keep them honest. So rather than assert on
 * field names, these tests parse the response with the same jjwt parser the validators use
 * and verify a real login token with the key that comes out. If that works, a validator can
 * do its job with nothing but this endpoint.
 */
class JwksEndpointIntegrationTest extends AbstractMockMvcIntegrationTest {

    private String fetchJwksJson() throws Exception {
        return mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Parses the document exactly as JwtKeyResolver does in every validator. */
    private static Map<String, PublicKey> parseAsAValidatorWould(String json) {
        JwkSet jwkSet = Jwks.setParser().ignoreUnsupported(true).build().parse(json);
        Map<String, PublicKey> keys = new java.util.LinkedHashMap<>();
        for (Jwk<?> jwk : jwkSet) {
            Key key = jwk.toKey();
            if (key instanceof PublicKey publicKey) {
                keys.put(jwk.getId(), publicKey);
            }
        }
        return keys;
    }

    private String loginAndGetAccessToken() throws Exception {
        long id = uniqueId();
        String email = "jwks" + id + "@finsight.test";
        register("user" + id, email, "trailhead lantern 88");
        return login(email, "trailhead lantern 88").path("accessToken").asText();
    }

    @Test
    void jwksIsPublic_andServesTheSigningKey() throws Exception {
        // No Authorization header: the callers of this endpoint are the validators deciding
        // whether a token is good, so requiring a token would be circular.
        Map<String, PublicKey> keys = parseAsAValidatorWould(fetchJwksJson());

        assertThat(keys).hasSize(1);
        assertThat(keys.values()).containsExactly(JwtTestTokens.publicKey());
    }

    /** The kid a validator reads off the token header to choose a key. */
    private static String kidOf(String accessToken) {
        return Jwts.parser().verifyWith(JwtTestTokens.publicKey()).build()
                .parseSignedClaims(accessToken).getHeader().getKeyId();
    }

    @Test
    void issuedTokensCarryAKidThatResolvesInTheJwks() throws Exception {
        String accessToken = loginAndGetAccessToken();
        Map<String, PublicKey> keys = parseAsAValidatorWould(fetchJwksJson());

        String kid = kidOf(accessToken);
        assertThat(kid).as("every minted token must name its signing key").isNotNull();
        assertThat(keys).containsKey(kid);
    }

    @Test
    void aTokenVerifiesWithTheKeyTakenFromTheJwks() throws Exception {
        String accessToken = loginAndGetAccessToken();
        Map<String, PublicKey> keys = parseAsAValidatorWould(fetchJwksJson());

        // The end-to-end claim this whole feature rests on: a validator holding nothing but
        // this document can pick the right key by kid and verify a token auth-service minted.
        PublicKey key = keys.get(kidOf(accessToken));

        assertThat(Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(accessToken).getPayload().getIssuer())
                .isEqualTo("finsight-auth");
    }

    @Test
    void theJwksNeverLeaksPrivateKeyMaterial() throws Exception {
        String json = fetchJwksJson();

        // An RSA private JWK is the public one plus "d" (and the CRT factors). Publishing any
        // of them would hand out the ability to mint tokens — the one thing this design exists
        // to prevent. Asserted on the raw JSON, because that is what actually goes on the wire.
        assertThat(json).contains("\"kty\":\"RSA\"", "\"n\":", "\"e\":");
        assertThat(json).doesNotContain("\"d\":", "\"p\":", "\"q\":", "PRIVATE");
    }
}
