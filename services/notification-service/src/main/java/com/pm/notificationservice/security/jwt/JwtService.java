package com.pm.notificationservice.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

/**
 * Validates JWT access tokens locally using the RSA <b>public</b> key published by
 * auth-service. The notification-service NEVER calls auth-service per request, and —
 * holding only the public key — it can verify tokens but cannot mint them.
 *
 * <p>Validation is identical to the api-gateway's edge check (docs/ADR-0002): RSA
 * signature + expiration (via {@code parseSignedClaims}), algorithm pinned to
 * <b>RS256</b>, issuer {@code == finsight-auth}, audience contains {@code finsight-api}.
 */
@Service
public class JwtService {

    /** Pinned signing algorithm; tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "RS256";

    private final PublicKey publicKey;
    private final String expectedIssuer;
    private final String expectedAudience;

    public JwtService(JwtProperties jwtProperties) {
        this.publicKey = parsePublicKey(jwtProperties.getPublicKey());
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
        // the RSA signature and enforces expiration (throws ExpiredJwtException).
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token);

        // verifyWith(PublicKey) accepts the whole RSA family (RS256/384/512), so pin the
        // algorithm explicitly to the one auth-service issues.
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

    private static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripArmor(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA public key in jwt.public-key", e);
        }
    }

    /** Strips optional PEM armor and all whitespace, leaving the bare base64 DER body. */
    private static String stripArmor(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }
}
