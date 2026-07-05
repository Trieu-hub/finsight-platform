package com.pm.dashboardservice.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JwtService} enforces the frozen contract (RS256 + issuer + audience +
 * expiry), matching the api-gateway. Tokens are signed with an ephemeral RSA keypair and
 * the service is configured with the matching public key. One test per requirement.
 */
class JwtServiceTest {

    private static final String ISSUER = "finsight-auth";
    private static final String AUDIENCE = "finsight-api";
    private static final KeyPair KEYS = generateRsa();

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setPublicKey(Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        props.setIssuer(ISSUER);
        props.setAudience(AUDIENCE);
        jwtService = new JwtService(props);
    }

    @Test
    void validTokenAccepted() {
        assertTrue(jwtService.validateToken(token(ISSUER, AUDIENCE, 3_600_000L, true)));
    }

    @Test
    void wrongIssuerRejected() {
        assertFalse(jwtService.validateToken(token("evil-issuer", AUDIENCE, 3_600_000L, true)));
    }

    @Test
    void wrongAudienceRejected() {
        assertFalse(jwtService.validateToken(token(ISSUER, "evil-api", 3_600_000L, true)));
    }

    @Test
    void wrongAlgorithmRejected() {
        // An HMAC-signed (HS256) token cannot be verified with the RSA public key.
        assertFalse(jwtService.validateToken(token(ISSUER, AUDIENCE, 3_600_000L, false)));
    }

    @Test
    void expiredTokenRejected() {
        assertFalse(jwtService.validateToken(token(ISSUER, AUDIENCE, -3_600_000L, true)));
    }

    private String token(String issuer, String audience, long ttlMillis, boolean rs256) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .claim("userId", 1L)
                .claim("email", "a@b.c")
                .claim("role", "USER")
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis));
        // rs256 == the pinned algorithm; otherwise sign HS256 (a wrong, HMAC alg) to prove rejection.
        return (rs256
                ? builder.signWith(KEYS.getPrivate(), Jwts.SIG.RS256)
                : builder.signWith(Keys.hmacShaKeyFor(
                        "0123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256))
                .compact();
    }

    private static KeyPair generateRsa() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
