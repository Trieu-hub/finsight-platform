package com.pm.authservice.security.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key rotation on the issuing side: which keys sign, which merely verify, and when a retired
 * key actually stops being honoured.
 *
 * <p>These are unit tests on purpose — rotation is a property of the key registry, and pinning
 * it here means the behaviour is checked in milliseconds rather than only via a container.
 */
class JwtKeyRotationTest {

    private static final String ISSUER = "finsight-auth";
    private static final String AUDIENCE = "finsight-api";

    private static final KeyPair OLD_KEYS = generateRsa();
    private static final KeyPair NEW_KEYS = generateRsa();

    private static String encode(java.security.Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static String kidOf(KeyPair keyPair) {
        return Jwks.builder().key((RSAPublicKey) keyPair.getPublic())
                .idFromThumbprint().build().getId();
    }

    /** The registry as configured mid-rotation (previous keys listed) or after it (empty). */
    private static JwtKeyRegistry registry(KeyPair signing, List<String> previousPublicKeys) {
        JwtProperties props = new JwtProperties();
        props.setPrivateKey(encode(signing.getPrivate()));
        props.setPublicKey(encode(signing.getPublic()));
        props.setPreviousPublicKeys(previousPublicKeys);
        return new JwtKeyRegistry(props);
    }

    private static JwtService serviceFor(JwtKeyRegistry keyRegistry) {
        JwtProperties props = new JwtProperties();
        props.setIssuer(ISSUER);
        props.setAudience(AUDIENCE);
        props.setAccessTokenExpiration(3_600_000L);
        return new JwtService(keyRegistry, props);
    }

    /** A token signed by the given pair, naming that key — i.e. one minted before a rotation. */
    private static String tokenSignedBy(KeyPair keyPair) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .header().keyId(kidOf(keyPair)).and()
                .subject("user@finsight.test")
                .claim("userId", 1L)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + 3_600_000L))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    @Test
    void theKidIsDerivedFromTheKeyItself_soIssuerAndValidatorAgreeWithoutConfig() {
        JwtKeyRegistry keyRegistry = registry(NEW_KEYS, List.of());

        // Nobody assigns this id: it is the RFC 7638 thumbprint of the key. That is why a
        // validator computing it independently arrives at the same answer.
        assertThat(keyRegistry.signingKeyId()).isEqualTo(kidOf(NEW_KEYS));
    }

    @Test
    void midRotation_theOutgoingKeyStillVerifiesButNoLongerSigns() {
        JwtKeyRegistry keyRegistry = registry(NEW_KEYS, List.of(encode(OLD_KEYS.getPublic())));

        // The point of the overlap window: tokens minted seconds before the rotation are still
        // in users' hands, and refusing them would log everyone out on every key change.
        assertThat(serviceFor(keyRegistry).validateToken(tokenSignedBy(OLD_KEYS))).isTrue();
        assertThat(serviceFor(keyRegistry).validateToken(tokenSignedBy(NEW_KEYS))).isTrue();

        // But only the new key mints.
        assertThat(keyRegistry.signingKeyId()).isEqualTo(kidOf(NEW_KEYS));
        assertThat(keyRegistry.verificationKeys()).containsOnlyKeys(kidOf(NEW_KEYS), kidOf(OLD_KEYS));
    }

    @Test
    void afterTheWindowCloses_theRetiredKeyIsRejected() {
        JwtKeyRegistry keyRegistry = registry(NEW_KEYS, List.of());

        // This is what makes rotation real rather than decorative: once the old key is dropped
        // from config, anything it signed is refused — including a token forged with it after a
        // compromise, which is the reason to rotate in the first place.
        assertThat(serviceFor(keyRegistry).validateToken(tokenSignedBy(OLD_KEYS))).isFalse();
    }

    @Test
    void theJwksAdvertisesEveryKeyThatVerifies() {
        JwtKeyRegistry keyRegistry = registry(NEW_KEYS, List.of(encode(OLD_KEYS.getPublic())));

        // A validator's whole view of "which keys are good" is this document, so anything the
        // registry accepts must appear in it — otherwise a validator would reject a token
        // auth-service itself considers valid.
        assertThat(keyRegistry.jwksJson()).contains(kidOf(NEW_KEYS), kidOf(OLD_KEYS));
    }

    @Test
    void blankAndDuplicatePreviousKeysAreTolerated() {
        // An unset JWT_PREVIOUS_PUBLIC_KEYS binds to a single empty string, and re-listing the
        // active key among the previous ones is an easy slip when following the rotation
        // runbook. Neither is a reason to refuse to start.
        JwtKeyRegistry keyRegistry = registry(NEW_KEYS,
                List.of("", encode(NEW_KEYS.getPublic())));

        assertThat(keyRegistry.verificationKeys()).containsOnlyKeys(kidOf(NEW_KEYS));
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
