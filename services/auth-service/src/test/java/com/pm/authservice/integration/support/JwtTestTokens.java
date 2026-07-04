package com.pm.authservice.integration.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Date;

/**
 * Mints RS256 JWTs for integration tests, mirroring the exact shape auth-service issues
 * (subject = email, plus userId/email/role claims). The keypair here is wired into the app
 * under test via {@code @DynamicPropertySource} (see {@code AbstractIntegrationTest}, which
 * sets {@code jwt.private-key}/{@code jwt.public-key} from it), so both login-minted tokens
 * and the ones built here verify against the same key.
 *
 * <p>NOTE: {@link #valid}/{@link #expired} deliberately omit issuer/audience — a JwtClaims
 * test relies on that to prove the enforced iss/aud rejects a token lacking them.
 */
public final class JwtTestTokens {

    private static final KeyPair KEYS = generateRsa();
    // A second, unrelated keypair to forge a structurally valid but wrongly-signed token.
    private static final KeyPair WRONG_KEYS = generateRsa();

    private JwtTestTokens() {
    }

    public static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
    }

    public static String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded());
    }

    /** The public key the app is configured to verify with (for tests that parse a token). */
    public static PublicKey publicKey() {
        return KEYS.getPublic();
    }

    /** A token valid for one hour, correctly signed (no iss/aud — see class note). */
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
                .subject(email)
                .claim("userId", userId)
                .claim("email", email)
                .claim("role", role)
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
