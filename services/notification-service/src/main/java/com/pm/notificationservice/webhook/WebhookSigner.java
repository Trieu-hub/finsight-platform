package com.pm.notificationservice.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Proves to a receiver that a webhook call really came from Vernfy, and that nobody edited it on
 * the way. Without this a webhook URL is a public endpoint anyone who learns it can forge alerts
 * into.
 *
 * <p>The scheme is the widely-copied Stripe/GitHub one, because the point of a signature is that
 * the receiving developer already knows how to check it:
 *
 * <pre>X-Vernfy-Signature: t=1754300000,v1=&lt;hex HMAC-SHA256&gt;</pre>
 *
 * <p>The signed message is {@code "<t>.<body>"}, not the body alone. Including the timestamp inside
 * the MAC is what makes it worth sending: a receiver can reject anything older than a few minutes
 * and know the timestamp itself was not rewritten, which turns a captured request from a
 * replayable forever-token into a short-lived one. The {@code v1} prefix leaves room to add a
 * second algorithm later without receivers guessing which one they are looking at.
 */
@Component
public class WebhookSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SECRET_PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A fresh shared secret. Prefixed so it is recognisable in a receiver's config and greppable in
     * a leak; 32 random bytes because that is the block size the HMAC keys off anyway.
     */
    public static String newSecret() {
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /** The full header value for one request. */
    public String header(String secret, long timestampSeconds, String body) {
        String signed = timestampSeconds + "." + body;
        return "t=" + timestampSeconds + ",v1=" + hmacHex(secret, signed);
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every JRE, so this cannot happen from configuration —
            // only from a broken runtime, which is not something a caller can handle.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
