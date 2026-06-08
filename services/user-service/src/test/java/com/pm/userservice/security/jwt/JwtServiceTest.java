package com.pm.userservice.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link JwtService} enforces the frozen contract (HS512 + issuer + audience +
 * expiry), matching the api-gateway. One test per requirement.
 */
class JwtServiceTest {

    private static final String SECRET =
            "test-secret-test-secret-test-secret-test-secret-0123456789abcdef";
    private static final String ISSUER = "finsight-auth";
    private static final String AUDIENCE = "finsight-api";

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
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
        // Same secret, but signed HS256 instead of the pinned HS512.
        assertFalse(jwtService.validateToken(token(ISSUER, AUDIENCE, 3_600_000L, false)));
    }

    @Test
    void expiredTokenRejected() {
        assertFalse(jwtService.validateToken(token(ISSUER, AUDIENCE, -3_600_000L, true)));
    }

    private String token(String issuer, String audience, long ttlMillis, boolean hs512) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .claim("userId", 1L)
                .claim("email", "a@b.c")
                .claim("role", "USER")
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis));
        return (hs512 ? builder.signWith(key, Jwts.SIG.HS512)
                      : builder.signWith(key, Jwts.SIG.HS256)).compact();
    }
}
