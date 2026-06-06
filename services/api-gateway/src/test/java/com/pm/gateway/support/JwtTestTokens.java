package com.pm.gateway.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Mints JWTs for gateway auth tests, mirroring the claim shape auth-service issues
 * (subject/userId/email/role + iss/aud) and the HS512 signing the gateway pins. Helper
 * variants deliberately violate one part of the contract each, to prove the gateway
 * rejects them.
 */
public final class JwtTestTokens {

    private JwtTestTokens() {
    }

    /** A fully valid HS512 token: correct issuer, audience, not expired. */
    public static String valid(String secret, String issuer, String audience) {
        return builder(secret, issuer, audience, 3_600_000L, Jwts.SIG.HS512);
    }

    /** Valid signature/iss/aud but already expired. */
    public static String expired(String secret, String issuer, String audience) {
        return builder(secret, issuer, audience, -3_600_000L, Jwts.SIG.HS512);
    }

    /** Wrong issuer (everything else valid). */
    public static String wrongIssuer(String secret, String audience) {
        return builder(secret, "evil-issuer", audience, 3_600_000L, Jwts.SIG.HS512);
    }

    /** Wrong audience (everything else valid). */
    public static String wrongAudience(String secret, String issuer) {
        return builder(secret, issuer, "evil-audience", 3_600_000L, Jwts.SIG.HS512);
    }

    /** Correct secret/iss/aud but signed HS256 instead of the pinned HS512. */
    public static String wrongAlgorithm(String secret, String issuer, String audience) {
        return builder(secret, issuer, audience, 3_600_000L, Jwts.SIG.HS256);
    }

    /** Valid structure but signed with a different (≥512-bit) secret → bad signature. */
    public static String badSignature(String issuer, String audience) {
        String other = "wrong-secret-wrong-secret-wrong-secret-wrong-secret-0123456789abcd";
        return builder(other, issuer, audience, 3_600_000L, Jwts.SIG.HS512);
    }

    private static String builder(String secret, String issuer, String audience,
                                  long ttlMillis, MacAlgorithm alg) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("user@finsight.test")
                .claim("userId", 1L)
                .claim("email", "user@finsight.test")
                .claim("role", "ROLE_USER")
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key, alg)
                .compact();
    }
}
