package com.pm.budgetservice.security.jwt;

import io.jsonwebtoken.Header;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the public key that verifies a given token, by the {@code kid} in its header.
 *
 * <p>Replaces "one key, frozen at startup" — which made rotation impossible without
 * restarting every validator in lockstep — with auth-service's JWK Set as the source of
 * truth. A token signed by a key this validator has never seen triggers a fetch, so a
 * rotation propagates on its own.
 *
 * <p><b>The configured key is a bootstrap, not a second source of truth.</b> The cache starts
 * seeded with {@code jwt.public-key} under its own thumbprint, so this validator can verify
 * tokens before it has ever reached auth-service — including the case where both restart
 * together and auth-service loses the race. But the first successful fetch <i>replaces</i>
 * the cache outright rather than merging into it. That is what makes rotation mean anything:
 * a key dropped from the JWK Set stops being honoured here too, even though it is still
 * sitting in this validator's environment. Merging would leave the old key trusted forever
 * and turn rotation into theatre.
 *
 * <p>A failed fetch keeps the last good keys rather than emptying the cache: auth-service
 * being down must not take every other service's authentication down with it. The cost is
 * that a rotation cannot complete while it is unreachable, which is the right trade — and
 * the failures are counted on {@code finsight.jwks.refresh.failed} so it is visible.
 */
@Component
public class JwtKeyResolver implements Locator<Key> {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyResolver.class);

    /** How long a successfully fetched key set is used before being refreshed. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    /**
     * Floor on the interval between fetch attempts. Without it, tokens bearing random
     * {@code kid}s — which anyone can mint, since the signature is checked afterwards — would
     * each miss the cache and turn this validator into a load generator against auth-service.
     * With it, an attacker gets one fetch per window no matter the request rate.
     */
    private static final Duration RETRY_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);

    private final String jwksUri;
    private final RestClient restClient;
    private final String bootstrapKeyId;
    private final Counter refreshFailedCounter;
    private final long cacheTtlMs;
    private final long retryCooldownMs;

    private volatile Map<String, PublicKey> keys;
    private volatile long lastSuccessMs;
    private volatile long lastAttemptMs;

    @Autowired
    public JwtKeyResolver(JwtProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, CACHE_TTL, RETRY_COOLDOWN);
    }

    /** Visible for tests, which cannot wait out a five-minute TTL to exercise a refresh. */
    JwtKeyResolver(JwtProperties properties, MeterRegistry meterRegistry,
                   Duration cacheTtl, Duration retryCooldown) {
        this.cacheTtlMs = cacheTtl.toMillis();
        this.retryCooldownMs = retryCooldown.toMillis();
        this.jwksUri = normalise(properties.getJwksUri());

        PublicKey bootstrapKey = parsePublicKey(properties.getPublicKey());
        this.bootstrapKeyId = keyIdOf(bootstrapKey);
        this.keys = Map.of(bootstrapKeyId, bootstrapKey);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory())
                .build();
        this.refreshFailedCounter = Counter.builder("finsight.jwks.refresh.failed")
                .description("JWK Set fetches that failed; the last known good keys stayed in use")
                .register(meterRegistry);

        if (jwksUri == null) {
            log.info("jwt.jwks-uri is not set; pinned to the single configured public key "
                    + "(kid={}) — key rotation will require a restart", bootstrapKeyId);
        }
    }

    @Override
    public Key locate(Header header) {
        String keyId = header instanceof ProtectedHeader protectedHeader
                ? protectedHeader.getKeyId()
                : null;
        // A token with no kid predates rotation support. Treating it as the bootstrap key's id
        // (rather than blindly returning that key) keeps it subject to the same rule as any
        // other: once the JWK Set stops listing that key, these stop verifying too.
        String wanted = keyId == null ? bootstrapKeyId : keyId;

        if (jwksUri == null) {
            return keys.get(wanted);
        }
        if (isStale()) {
            refresh();
        }
        PublicKey key = keys.get(wanted);
        if (key == null) {
            // An unknown kid is the signal that a rotation happened early — before the TTL was
            // up. Fetch now rather than reject a token that is very likely fine.
            refresh();
            key = keys.get(wanted);
        }
        if (key == null) {
            log.warn("No verification key for kid={} after refreshing {}; rejecting the token",
                    wanted, jwksUri);
        }
        // Returning null makes jjwt reject the token, which is what we want: an unknown key is
        // an untrusted key.
        return key;
    }

    private boolean isStale() {
        return System.currentTimeMillis() - lastSuccessMs > cacheTtlMs;
    }

    private void refresh() {
        if (!cooldownElapsed()) {
            return;
        }
        synchronized (this) {
            // Re-check inside the lock: a burst of requests must produce one fetch, not one each.
            if (!cooldownElapsed()) {
                return;
            }
            // Stamp before the call, so a hanging auth-service cannot be retried by every
            // request that piles up behind it.
            lastAttemptMs = System.currentTimeMillis();
            try {
                String body = restClient.get().uri(jwksUri).retrieve().body(String.class);
                Map<String, PublicKey> fetched = parseJwkSet(body);
                if (fetched.isEmpty()) {
                    throw new IllegalStateException("JWK Set contained no usable RSA public keys");
                }
                keys = fetched;
                lastSuccessMs = System.currentTimeMillis();
                log.debug("Refreshed JWK Set from {}: {} key(s)", jwksUri, fetched.size());
            } catch (Exception e) {
                refreshFailedCounter.increment();
                log.warn("Could not refresh the JWK Set from {}; continuing with {} cached key(s)",
                        jwksUri, keys.size(), e);
            }
        }
    }

    private boolean cooldownElapsed() {
        return System.currentTimeMillis() - lastAttemptMs >= retryCooldownMs;
    }

    /**
     * Keeps only RSA public keys. RS256 is pinned by docs/ADR-0002 §1, so anything else in the
     * document is not something this validator should ever verify with.
     */
    private static Map<String, PublicKey> parseJwkSet(String json) {
        JwkSet jwkSet = Jwks.setParser().ignoreUnsupported(true).build().parse(json);
        Map<String, PublicKey> parsed = new LinkedHashMap<>();
        for (Jwk<?> jwk : jwkSet) {
            Key key = jwk.toKey();
            if (jwk.getId() != null && key instanceof RSAPublicKey rsaKey) {
                parsed.put(jwk.getId(), rsaKey);
            }
        }
        return Map.copyOf(parsed);
    }

    private static String keyIdOf(PublicKey key) {
        if (!(key instanceof RSAPublicKey rsaKey)) {
            throw new IllegalStateException("jwt.public-key must be RSA; got " + key.getAlgorithm());
        }
        // The same RFC 7638 thumbprint auth-service derives its kid from, so the two agree on
        // the identity of a key without ever exchanging a name for it.
        return Jwks.builder().key(rsaKey).idFromThumbprint().build().getId();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build());
        // This fetch can happen on a request thread, so it must fail fast: a hung auth-service
        // must not hold an authentication check open.
        factory.setReadTimeout(HTTP_TIMEOUT);
        return factory;
    }

    private static String normalise(String uri) {
        return uri == null || uri.isBlank() ? null : uri.trim();
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripArmor(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA public key in jwt.public-key", e);
        }
    }

    /** Strips optional PEM armor and all whitespace, leaving the bare base64 DER body. */
    private static String stripArmor(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }
}
