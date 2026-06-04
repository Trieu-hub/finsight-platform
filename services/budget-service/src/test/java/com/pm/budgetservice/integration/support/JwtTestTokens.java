package com.pm.budgetservice.integration.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Mints HS256 JWTs for integration tests, mirroring the claim shape
 * (userId / email / role) that auth-service issues and JwtService consumes.
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
                .claim("userId", userId)
                .claim("email", email)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }
}
