package com.pm.notificationservice.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Validates JWT access tokens locally using the secret shared with auth-service.
 * The notification-service NEVER calls auth-service per request.
 *
 * <p>Validation is identical to the api-gateway's edge check (docs/ADR-0002): HMAC
 * signature + expiration (via {@code parseSignedClaims}), algorithm pinned to
 * <b>HS512</b>, issuer {@code == finsight-auth}, audience contains {@code finsight-api}.
 */
@Service
public class JwtService {

    /** Pinned signing algorithm; tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "HS512";

    private final SecretKey signingKey;
    private final String expectedIssuer;
    private final String expectedAudience;

    public JwtService(JwtProperties jwtProperties) {
        this.signingKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expectedIssuer = jwtProperties.getIssuer();
        this.expectedAudience = jwtProperties.getAudience();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long extractUserId(String token) {
        Object userId = parseClaims(token).get("userId");
        return ((Number) userId).longValue();
    }

    public String extractEmail(String token) {
        return parseClaims(token).get("email", String.class);
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    private Claims parseClaims(String token) {
        // verifyWith() rejects unsecured ('none') tokens; parseSignedClaims() verifies
        // the HMAC signature and enforces expiration (throws ExpiredJwtException).
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);

        // verifyWith(SecretKey) alone would also accept HS256/HS384 signed with the
        // same secret, so pin the algorithm explicitly.
        if (!REQUIRED_ALG.equals(jws.getHeader().getAlgorithm())) {
            throw new JwtException("Unexpected JWT algorithm: " + jws.getHeader().getAlgorithm());
        }

        Claims claims = jws.getPayload();
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new JwtException("Unexpected JWT issuer: " + claims.getIssuer());
        }
        Set<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(expectedAudience)) {
            throw new JwtException("JWT audience does not contain " + expectedAudience);
        }
        return claims;
    }
}
