package com.pm.gateway.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

/**
 * Mints JWTs for gateway auth tests, mirroring the claim shape auth-service issues
 * (subject/userId/email/role + iss/aud) and the RS256 signing the gateway pins. The
 * matching public key ({@link #publicKeyBase64()}) is fed to the gateway under test via
 * {@code @DynamicPropertySource}. Helper variants deliberately violate one part of the
 * contract each, to prove the gateway rejects them.
 */
public final class JwtTestTokens {

    private static final KeyPair KEYS = generateRsa();
    // A second, unrelated keypair to forge a structurally valid but wrongly-signed token.
    private static final KeyPair WRONG_KEYS = generateRsa();

    private JwtTestTokens() {
    }

    /** Base64-encoded DER of the public key the gateway must be configured to verify with. */
    public static String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded());
    }

    /** A fully valid RS256 token: correct issuer, audience, not expired. */
    public static String valid(String issuer, String audience) {
        return rs256(KEYS, issuer, audience, 3_600_000L);
    }

    /** Valid signature/iss/aud but already expired. */
    public static String expired(String issuer, String audience) {
        return rs256(KEYS, issuer, audience, -3_600_000L);
    }

    /** Wrong issuer (everything else valid). */
    public static String wrongIssuer(String audience) {
        return rs256(KEYS, "evil-issuer", audience, 3_600_000L);
    }

    /** Wrong audience (everything else valid). */
    public static String wrongAudience(String issuer) {
        return rs256(KEYS, issuer, "evil-audience", 3_600_000L);
    }

    /** Correct iss/aud but signed HS256 (an HMAC alg) instead of the pinned RS256. */
    public static String wrongAlgorithm(String issuer, String audience) {
        var key = Keys.hmacShaKeyFor(
                "0123456789012345678901234567890123456789".getBytes(StandardCharsets.UTF_8));
        return base(issuer, audience, 3_600_000L).signWith(key, Jwts.SIG.HS256).compact();
    }

    /** Valid structure but signed with an unrelated RSA key → bad signature. */
    public static String badSignature(String issuer, String audience) {
        return rs256(WRONG_KEYS, issuer, audience, 3_600_000L);
    }

    private static String rs256(KeyPair keys, String issuer, String audience, long ttlMillis) {
        return base(issuer, audience, ttlMillis).signWith(keys.getPrivate(), Jwts.SIG.RS256).compact();
    }

    private static io.jsonwebtoken.JwtBuilder base(String issuer, String audience, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("user@finsight.test")
                .claim("userId", 1L)
                .claim("email", "user@finsight.test")
                .claim("role", "ROLE_USER")
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis));
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
