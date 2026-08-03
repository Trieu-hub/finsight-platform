package com.pm.notificationservice.push;

import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Builds the {@code Authorization} header of a Web Push request (RFC 8292, "VAPID").
 *
 * <p>The header carries a short-lived ES256 JWT signed with the application's private key, plus
 * the matching public key in the clear. The push service checks that the two agree, which is what
 * stops anyone who has merely seen a subscription endpoint from pushing to it.
 *
 * <p>This is deliberately the whole of the crypto here. Because the pushes carry <b>no payload</b>
 * (the service worker fetches the notification over the normal API), the content encryption of
 * RFC 8291 — ECDH against the subscription's own key, HKDF, AES128GCM — is not needed, and with it
 * goes the reason most projects pull in a web-push library. Signing an ES256 JWT is something the
 * jjwt already on the classpath does.
 */
@Component
public class VapidSigner {

    /** Max allowed by RFC 8292 is 24 h; half that leaves room for clock skew either way. */
    private static final long TOKEN_TTL_SECONDS = 12 * 3600;

    private final PushProperties properties;

    public VapidSigner(PushProperties properties) {
        this.properties = properties;
    }

    /**
     * @param endpoint the subscription endpoint the push will be POSTed to
     * @return the value for the {@code Authorization} header
     */
    @SuppressWarnings("deprecation") // single(): see the comment on the audience below
    public String authorizationHeader(String endpoint) {
        // The token is bound to the push service's origin, not to the full endpoint: a token
        // minted for Chrome's service is useless against Firefox's.
        URI uri = URI.create(endpoint);
        String origin = uri.getScheme() + "://" + uri.getAuthority();

        String token = Jwts.builder()
                // `aud` must be a bare string. jjwt deprecates single() because RFC 7519 prefers
                // the array form, but FCM rejects an array outright — measured against a real
                // subscription: string => 201 Created, ["..."] => 403 "permission denied: invalid
                // aud claim". Parsing normalises both back to a Set, so only the wire form tells
                // them apart; the regression test asserts on the raw payload JSON for that reason.
                .audience().single(origin)
                .subject(properties.getSubject())
                .expiration(Date.from(Instant.now().plusSeconds(TOKEN_TTL_SECONDS)))
                .signWith(privateKey(), Jwts.SIG.ES256)
                .compact();

        return "vapid t=" + token + ", k=" + properties.getPublicKey();
    }

    /**
     * Rebuilds the P-256 private key from the raw 32-byte scalar that VAPID tooling emits (it is
     * not a PKCS#8 blob, so {@code PKCS8EncodedKeySpec} cannot be used).
     */
    private PrivateKey privateKey() {
        byte[] scalar;
        try {
            scalar = Base64.getUrlDecoder().decode(properties.getPrivateKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("finsight.push.private-key is not valid base64url", e);
        }
        // Checked explicitly because a truncated key is NOT rejected downstream: any byte string
        // is a usable scalar, so it would sign happily and only surface as 401/403 from the push
        // service — a failure mode with no local symptom at all.
        if (scalar.length != 32) {
            throw new IllegalStateException(
                    "finsight.push.private-key must decode to 32 bytes (got " + scalar.length
                            + ") — regenerate it with scripts/gen-vapid-keys.sh");
        }
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);

            // Unsigned: a scalar with its top bit set would otherwise decode as a negative number.
            BigInteger s = new BigInteger(1, scalar);

            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(s, curve));
        } catch (Exception e) {
            throw new IllegalStateException("finsight.push.private-key is not a valid VAPID P-256 key", e);
        }
    }
}
