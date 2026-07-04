package com.pm.dashboardservice.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

/**
 * Mints RS256 JWTs for dashboard security tests, mirroring the claim shape auth-service
 * issues (userId/email/role + iss/aud). The matching public key ({@link #publicKeyBase64()})
 * is fed to the app under test via {@code @DynamicPropertySource}. Variants violate one part
 * of the contract each, to prove the filter rejects them.
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

    /** A fully valid RS256 token: correct issuer, audience, not expired. */
    public static String valid(String issuer, String audience) {
        return rs256(KEYS, issuer, audience, 3_600_000L);
    }

    /** Valid signature/iss/aud but already expired. */
    public static String expired(String issuer, String audience) {
        return rs256(KEYS, issuer, audience, -3_600_000L);
    }

    /** Valid structure but signed with an unrelated RSA key → bad signature. */
    public static String forgedSignature(String issuer, String audience) {
        return rs256(WRONG_KEYS, issuer, audience, 3_600_000L);
    }

    private static String rs256(KeyPair keys, String issuer, String audience, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claim("userId", 1L)
                .claim("email", "a@b.c")
                .claim("role", "USER")
                .issuer(issuer)
                .audience().add(audience).and()
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
