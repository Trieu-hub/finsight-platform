package com.pm.notificationservice.integration.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

/**
 * Mints RS256 JWTs for integration tests using an ephemeral RSA keypair. The matching
 * public key ({@link #publicKeyBase64()}) is fed to the app under test via
 * {@code @DynamicPropertySource} (see {@code AbstractMySqlIntegrationTest}), so tokens
 * minted here verify exactly as auth-service's real tokens would. Mirrors the claim shape
 * (userId / email / role + iss/aud) the service consumes.
 */
public final class JwtTestTokens {

    private static final KeyPair KEYS = generateRsa();
    // A second, unrelated keypair to forge a structurally valid but wrongly-signed token.
    private static final KeyPair WRONG_KEYS = generateRsa();

    private JwtTestTokens() {
    }

    /** Base64-encoded DER of the public key the app must be configured to verify with. */
    public static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
    }

    /** A token valid for one hour, correctly signed. */
    public static String valid(long userId, String email, String role) {
        return build(KEYS, userId, email, role, 3_600_000L);
    }

    /** A token whose expiry is already in the past. */
    public static String expired(long userId, String email, String role) {
        return build(KEYS, userId, email, role, -3_600_000L);
    }

    /** Correct shape but signed by an unrelated key → fails signature verification. */
    public static String forgedSignature(long userId, String email, String role) {
        return build(WRONG_KEYS, userId, email, role, 3_600_000L);
    }

    private static String build(KeyPair keys, long userId, String email, String role, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claim("userId", userId)
                .claim("email", email)
                .claim("role", role)
                .issuer("finsight-auth")
                .audience().add("finsight-api").and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(keys.getPrivate(), Jwts.SIG.RS256)
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
