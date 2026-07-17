package com.pm.gateway.security;

import com.pm.gateway.config.JwtProperties;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validator half of key rotation, against a real HTTP JWK Set.
 *
 * <p>Served by the JDK's own {@link HttpServer} rather than a mock: what is under test is
 * whether this resolver can consume the document auth-service actually publishes — the two
 * share no library and agree only on JSON and a thumbprint — so stubbing the fetch would test
 * nothing but the stub. The bodies here are built with the same jjwt calls auth-service's
 * {@code JwtKeyRegistry} uses.
 */
class JwtKeyResolverTest {

    private static final KeyPair BOOTSTRAP_KEYS = generateRsa();
    private static final KeyPair ROTATED_KEYS = generateRsa();

    private HttpServer server;
    private SimpleMeterRegistry meterRegistry;
    private final AtomicReference<String> jwksBody = new AtomicReference<>();
    private final AtomicInteger fetchCount = new AtomicInteger();
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void startJwksServer() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        jwksBody.set(jwksOf(BOOTSTRAP_KEYS));
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            fetchCount.incrementAndGet();
            byte[] body = jwksBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopJwksServer() {
        server.stop(0);
    }

    private String jwksUri() {
        return "http://localhost:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /** Renders a JWK Set exactly as auth-service's JwtKeyRegistry does. */
    private static String jwksOf(KeyPair... keyPairs) {
        StringBuilder json = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < keyPairs.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(Jwks.json(Jwks.builder().key((RSAPublicKey) keyPairs[i].getPublic())
                    .idFromThumbprint().build()));
        }
        return json.append("]}").toString();
    }

    private static String kidOf(KeyPair keyPair) {
        return Jwks.builder().key((RSAPublicKey) keyPair.getPublic())
                .idFromThumbprint().build().getId();
    }

    /** Production timings: a five-minute cache and a thirty-second floor between fetches. */
    private JwtKeyResolver resolver(String jwksUri) {
        return resolver(jwksUri, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    private JwtKeyResolver resolver(String jwksUri, Duration cacheTtl, Duration retryCooldown) {
        JwtProperties props = new JwtProperties();
        props.setPublicKey(Base64.getEncoder()
                .encodeToString(BOOTSTRAP_KEYS.getPublic().getEncoded()));
        props.setJwksUri(jwksUri);
        return new JwtKeyResolver(props, meterRegistry, cacheTtl, retryCooldown);
    }

    private double refreshFailures() {
        return meterRegistry.get("finsight.jwks.refresh.failed").counter().count();
    }

    /** A signed token naming its key. */
    private static String tokenSignedBy(KeyPair keyPair) {
        return token(keyPair, kidOf(keyPair));
    }

    private static String token(KeyPair keyPair, String kid) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder();
        if (kid != null) {
            builder.header().keyId(kid).and();
        }
        return builder
                .subject("user@finsight.test")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 3_600_000L))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** Verifies a token using only what the resolver hands back — the production path. */
    private static boolean verifies(JwtKeyResolver resolver, String jwt) {
        try {
            Jwts.parser().keyLocator(resolver).build().parseSignedClaims(jwt);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void withoutAJwksUri_theConfiguredKeyIsUsedAndNothingIsFetched() {
        JwtKeyResolver resolver = resolver(null);

        assertThat(verifies(resolver, tokenSignedBy(BOOTSTRAP_KEYS))).isTrue();
        assertThat(fetchCount).hasValue(0);
    }

    @Test
    void aKeyKnownOnlyToTheJwksVerifies_provingTheDocumentIsUnderstood() {
        jwksBody.set(jwksOf(ROTATED_KEYS));
        JwtKeyResolver resolver = resolver(jwksUri());

        // This key exists only in the published set, never in the validator's configuration,
        // so passing means it genuinely came off the wire and was parsed correctly.
        assertThat(verifies(resolver, tokenSignedBy(ROTATED_KEYS))).isTrue();
        assertThat(fetchCount.get()).isPositive();
    }

    @Test
    void theFetchedSetReplacesTheBootstrapKeyRatherThanAddingToIt() {
        jwksBody.set(jwksOf(ROTATED_KEYS));
        JwtKeyResolver resolver = resolver(jwksUri());

        // The bootstrap key is still sitting in this validator's environment, but it is gone
        // from the published set — so it must stop verifying. Were the resolver to merge rather
        // than replace, a retired (or compromised) key would stay trusted for as long as the env
        // var survived, and rotation would be theatre.
        assertThat(verifies(resolver, tokenSignedBy(BOOTSTRAP_KEYS))).isFalse();
    }

    @Test
    void duringTheOverlapWindow_bothTheOutgoingAndIncomingKeyVerify() {
        jwksBody.set(jwksOf(ROTATED_KEYS, BOOTSTRAP_KEYS));
        JwtKeyResolver resolver = resolver(jwksUri());

        assertThat(verifies(resolver, tokenSignedBy(ROTATED_KEYS))).isTrue();
        assertThat(verifies(resolver, tokenSignedBy(BOOTSTRAP_KEYS))).isTrue();
    }

    @Test
    void whenTheJwksIsUnreachable_theBootstrapKeyStillVerifies() {
        // auth-service being down — or merely slower to start — must not take authentication
        // down across all seven validators; that turns one service's outage into a total one.
        JwtKeyResolver resolver = resolver("http://localhost:1/.well-known/jwks.json");

        assertThat(verifies(resolver, tokenSignedBy(BOOTSTRAP_KEYS))).isTrue();
        assertThat(refreshFailures()).isPositive();
    }

    @Test
    void aFailedRefreshKeepsTheKeysAlreadyLearned_andIsCounted() {
        jwksBody.set(jwksOf(ROTATED_KEYS));
        // Zero TTL and no cooldown: every lookup refreshes, so the failure path is actually
        // reached instead of being skipped by a still-warm cache.
        JwtKeyResolver resolver = resolver(jwksUri(), Duration.ZERO, Duration.ZERO);
        assertThat(verifies(resolver, tokenSignedBy(ROTATED_KEYS))).isTrue();

        responseStatus.set(500);

        // Losing contact with auth-service must not invalidate keys already known to be good —
        // the alternative is that a blip in one service rejects every token in the platform.
        assertThat(verifies(resolver, tokenSignedBy(ROTATED_KEYS))).isTrue();
        assertThat(refreshFailures()).isPositive();
    }

    @Test
    void unknownKidsCannotStampedeAuthService() {
        JwtKeyResolver resolver = resolver(jwksUri());
        fetchCount.set(0);

        // Anyone can mint a token bearing a random kid — the signature is only checked after the
        // key is looked up. Without a floor on the interval between fetches, each miss would
        // become a request to auth-service, making this validator a ready-made DoS amplifier.
        for (int i = 0; i < 50; i++) {
            verifies(resolver, token(ROTATED_KEYS, "kid-that-does-not-exist-" + i));
        }

        assertThat(fetchCount.get())
                .as("50 unknown-kid tokens must not become 50 JWKS fetches")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void aTokenWithNoKidUsesTheBootstrapKey() {
        // Tokens minted by the build that predates kid support must keep working across the
        // deploy that introduces it, or the release logs every active user out.
        JwtKeyResolver resolver = resolver(null);

        assertThat(verifies(resolver, token(BOOTSTRAP_KEYS, null))).isTrue();
    }

    @Test
    void anUnknownKeyIsRejectedRatherThanFallingBack() {
        JwtKeyResolver resolver = resolver(null);

        // Falling back to the bootstrap key on an unknown kid would look harmless — the
        // signature check would fail anyway — but it would mean an attacker chooses which key
        // gets tried. Unknown means rejected.
        assertThat(verifies(resolver, tokenSignedBy(ROTATED_KEYS))).isFalse();
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
