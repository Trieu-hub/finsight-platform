package com.pm.notificationservice.push;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The VAPID header is the whole of the crypto in the push channel, and it fails silently in the
 * worst way — a push service just answers 401 and the user quietly stops getting notifications.
 * So these tests verify the signature against the public half rather than merely asserting shape.
 */
class VapidSignerTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/cIH-abc123";

    private PushProperties properties;
    private ECPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        publicKey = (ECPublicKey) pair.getPublic();

        properties = new PushProperties();
        properties.setPrivateKey(base64Url(toFixedLength(((ECPrivateKey) pair.getPrivate()).getS())));
        properties.setPublicKey(base64Url(uncompressedPoint(publicKey)));
        properties.setSubject("https://vernfy.com");
    }

    @Test
    void producesAVapidHeaderCarryingTheConfiguredPublicKey() {
        String header = new VapidSigner(properties).authorizationHeader(ENDPOINT);

        assertThat(header).startsWith("vapid t=");
        assertThat(header).endsWith(", k=" + properties.getPublicKey());
    }

    @Test
    void signsATokenTheMatchingPublicKeyCanVerify() {
        String token = tokenFrom(new VapidSigner(properties).authorizationHeader(ENDPOINT));

        // Verifying with the public half is what proves the 32-byte scalar was rebuilt correctly.
        // A truncated key still *signs*, so only this assertion catches that class of bug.
        Jws<Claims> jws = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);

        assertThat(jws.getPayload().getSubject()).isEqualTo("https://vernfy.com");
        assertThat(jws.getPayload().getExpiration()).isAfter(new Date());
    }

    @Test
    void scopesTheTokenToThePushServiceOriginNotTheFullEndpoint() {
        String token = tokenFrom(new VapidSigner(properties).authorizationHeader(ENDPOINT));

        Claims claims = Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();

        // The audience must be the origin: a token minted for Chrome's push service is then
        // useless against Firefox's, and it does not leak which subscription it was for.
        assertThat(claims.getAudience()).containsExactly("https://fcm.googleapis.com");
    }

    @Test
    void failsLoudlyOnATruncatedKeyInsteadOfSigningWithIt() {
        // The failure this guards against is real: an earlier gen-vapid-keys.sh dropped the last
        // octet, and a 31-byte scalar signs perfectly well — the only symptom would have been the
        // push service answering 401 in production. Length is the one thing worth asserting here.
        properties.setPrivateKey(base64Url(new byte[31]));

        assertThatThrownBy(() -> new VapidSigner(properties).authorizationHeader(ENDPOINT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must decode to 32 bytes");
    }

    private static String tokenFrom(String header) {
        return header.substring("vapid t=".length(), header.indexOf(", k="));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** BigInteger drops leading zero bytes and may add a sign byte; VAPID wants exactly 32. */
    private static byte[] toFixedLength(BigInteger scalar) {
        byte[] raw = scalar.toByteArray();
        byte[] fixed = new byte[32];
        if (raw.length >= 32) {
            System.arraycopy(raw, raw.length - 32, fixed, 0, 32);
        } else {
            System.arraycopy(raw, 0, fixed, 32 - raw.length, raw.length);
        }
        return fixed;
    }

    private static byte[] uncompressedPoint(ECPublicKey key) {
        byte[] x = toFixedLength(key.getW().getAffineX());
        byte[] y = toFixedLength(key.getW().getAffineY());
        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(x, 0, point, 1, 32);
        System.arraycopy(y, 0, point, 33, 32);
        return point;
    }
}
