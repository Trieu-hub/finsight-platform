package com.pm.notificationservice.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Validates JWT access tokens locally using the RSA <b>public</b> key published by
 * auth-service. The notification-service NEVER calls auth-service per request, and —
 * holding only the public key — it can verify tokens but cannot mint them.
 *
 * <p>Validation is identical to the api-gateway's edge check (docs/ADR-0002): RSA
 * signature + expiration (via {@code parseSignedClaims}), algorithm pinned to
* <b>RS256</b>, issuer {@code == finsight-auth}, audience contains {@code finsight-api}.
 *
 * <p><b>Which</b> public key verifies a given token is decided by the token's {@code kid}
 * against auth-service's published JWK Set -- see {@link JwtKeyResolver} -- rather than a single
 * key pinned at startup, so a key can be rotated without restarting this service. That lookup
 * is cached, so it remains true that there is no call to auth-service per request, and it
 * falls back to the configured key while the JWK Set is unreachable.
 */
@Service
public class JwtService {

    /** Pinned signing algorithm; tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "RS256";

    private final JwtKeyResolver keyResolver;
    private final String expectedIssuer;
    private final String expectedAudience;

    public JwtService(JwtProperties jwtProperties, JwtKeyResolver keyResolver) {
        this.keyResolver = keyResolver;
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
        // The key is chosen from the token's own kid via auth-service's JWK Set (see
        // JwtKeyResolver), not pinned at startup -- that is what lets a key be rotated without
        // restarting this service. parseSignedClaims() still demands a *signed* JWT, so an
        // unsecured ('none') token is rejected before any key is consulted, and an unknown kid
        // resolves to no key and fails the same way. Expiration is enforced here too.
        Jws<Claims> jws = Jwts.parser()
                .keyLocator(keyResolver)
                .build()
                .parseSignedClaims(token);

        // An RSA public key would also verify RS384/RS512, so pin the algorithm explicitly to
        // the one auth-service issues.
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
