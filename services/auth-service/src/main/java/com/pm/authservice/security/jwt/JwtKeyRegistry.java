package com.pm.authservice.security.jwt;

import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.RsaPublicJwk;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The signing key auth-service currently mints with, plus every public key that validators
 * must still accept. This is the whole of key rotation on the issuing side.
 *
 * <p>Rotation needs an overlap window: at the moment the signing key changes, tokens signed
 * by the outgoing key are still in users' hands for up to the access-token lifetime. Serving
 * only the new key would invalidate all of them at once — a forced logout for every active
 * user. So {@code jwt.previous-public-keys} holds the outgoing key(s) for that window; they
 * verify but never sign. Once the window has passed they are dropped from config, and any
 * token they signed dies with them.
 *
 * <p>Each key's {@code kid} is its <b>RFC 7638 JWK thumbprint</b> — a hash of the key material
 * itself, not a name someone assigns. That is deliberate: an operator rotating keys cannot
 * forget to bump an id, cannot collide two keys under one id, and cannot mismatch the id
 * between issuer and validator. The same key always yields the same kid on both sides, so the
 * two agree without sharing any config beyond the key itself.
 */
@Component
public class JwtKeyRegistry {

    private final PrivateKey signingKey;
    private final String signingKeyId;
    private final Map<String, PublicKey> verificationKeys;
    private final String jwksJson;

    public JwtKeyRegistry(JwtProperties jwtProperties) {
        this.signingKey = parsePrivateKey(jwtProperties.getPrivateKey());

        PublicKey activeKey = parsePublicKey(jwtProperties.getPublicKey(), "jwt.public-key");
        this.signingKeyId = keyIdOf(activeKey);

        // Active key first so it is the one a validator picks up by default; insertion order
        // is preserved into the published JWKS.
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        keys.put(signingKeyId, activeKey);

        List<String> previous = jwtProperties.getPreviousPublicKeys();
        if (previous != null) {
            for (String pem : previous) {
                if (pem == null || pem.isBlank()) {
                    continue; // an unset env var binds to a single empty entry
                }
                PublicKey key = parsePublicKey(pem, "jwt.previous-public-keys");
                // putIfAbsent: re-listing the active key among the previous ones is a harmless
                // operator slip during rotation, not a reason to refuse to start.
                keys.putIfAbsent(keyIdOf(key), key);
            }
        }

        this.verificationKeys = Collections.unmodifiableMap(keys);
        this.jwksJson = buildJwksJson(keys);
    }

    /** The private key tokens are signed with. Never leaves this service. */
    public PrivateKey signingKey() {
        return signingKey;
    }

    /** The {@code kid} written into the header of every token this service mints. */
    public String signingKeyId() {
        return signingKeyId;
    }

    /** Every public key still accepted, by {@code kid}: the active one plus any in the overlap window. */
    public Map<String, PublicKey> verificationKeys() {
        return verificationKeys;
    }

    /** The public JWKS document served at {@code /.well-known/jwks.json}, serialized once. */
    public String jwksJson() {
        return jwksJson;
    }

    /**
     * Renders the JWK Set with jjwt's own serializer, one key at a time.
     *
     * <p>Not Jackson: jjwt wraps a JWK's members in redacting suppliers so that a stray
     * {@code toString()} cannot spill key material, and a generic bean serializer writes those
     * wrappers rather than the values — producing a {@code "keys"} object instead of the array
     * RFC 7517 requires, which no validator can parse.
     *
     * <p>{@link Jwks#json(io.jsonwebtoken.security.PublicJwk)} accepts a <b>public</b> JWK only.
     * That parameter type is the real guard on this method: publishing private key material is
     * the one failure this whole design exists to prevent, and here it would not compile.
     */
    private static String buildJwksJson(Map<String, PublicKey> keys) {
        return keys.values().stream()
                .map(JwtKeyRegistry::toJwk)
                .map(Jwks::json)
                .collect(Collectors.joining(",", "{\"keys\":[", "]}"));
    }

    private static String keyIdOf(PublicKey key) {
        return toJwk(key).getId();
    }

    private static RsaPublicJwk toJwk(PublicKey key) {
        if (!(key instanceof RSAPublicKey rsaKey)) {
            throw new IllegalStateException(
                    "JWT keys must be RSA (RS256 is pinned by docs/ADR-0005); got "
                            + key.getAlgorithm());
        }
        return Jwks.builder().key(rsaKey).idFromThumbprint().build();
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripArmor(pem));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA private key in jwt.private-key", e);
        }
    }

    private static PublicKey parsePublicKey(String pem, String property) {
        try {
            byte[] der = Base64.getDecoder().decode(stripArmor(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA public key in " + property, e);
        }
    }

    /** Strips optional PEM armor and all whitespace, leaving the bare base64 DER body. */
    private static String stripArmor(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }
}
