package com.pm.authservice.integration.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Mints HS256 JWTs for integration tests, mirroring the exact shape auth-service
 * issues (subject = email, plus userId/email/role claims) so the real JwtService
 * and JwtAuthenticationFilter accept or reject them as in production.
 */
public final class JwtTestTokens {

    private JwtTestTokens() {
    }

    /** A token valid for one hour. */
    public static String valid(String secret, long userId, String email, String role) {
        return build(secret, userId, email, role, 3_600_000L);
    }

    /** A token whose expiry is already in the past. */
    public static String expired(String secret, long userId, String email, String role) {
        return build(secret, userId, email, role, -3_600_000L);
    }

    private static String build(String secret, long userId, String email, String role, long ttlMillis) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .claim("userId", userId)
                .claim("email", email)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }
}
