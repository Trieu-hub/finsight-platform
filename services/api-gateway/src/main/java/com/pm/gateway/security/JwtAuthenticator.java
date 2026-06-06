package com.pm.gateway.security;

import com.pm.gateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Edge JWT validation (Phase 2). Verifies the access token's signature and enforces
 * the frozen contract (docs/ADR-0002): algorithm pinned to <b>HS512</b>, issuer
 * {@code == finsight-auth}, audience set contains {@code finsight-api}.
 *
 * <p>This validation is additive: the gateway rejects bad tokens early, but every
 * downstream service still validates the token itself (the V1 invariant — the gateway
 * is removable without service changes). The bearer token is therefore forwarded
 * unchanged by the proxy.
 *
 * <p>Maps failures to the three frozen authentication outcomes so the proxy can emit
 * the correct error code:
 * <ul>
 *   <li>{@link Outcome#MISSING} — no / malformed {@code Authorization: Bearer} header.</li>
 *   <li>{@link Outcome#EXPIRED} — signature valid but {@code exp} is in the past.</li>
 *   <li>{@link Outcome#INVALID} — bad signature, wrong {@code alg}, failed
 *       {@code iss}/{@code aud}, or otherwise unparseable.</li>
 * </ul>
 */
@Component
public class JwtAuthenticator {

    /** The pinned signing algorithm. Tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "HS512";
    private static final String BEARER_PREFIX = "Bearer ";

    public enum Outcome { AUTHENTICATED, MISSING, EXPIRED, INVALID }

    private final SecretKey signingKey;
    private final String expectedIssuer;
    private final String expectedAudience;

    public JwtAuthenticator(JwtProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(
                properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expectedIssuer = properties.getIssuer();
        this.expectedAudience = properties.getAudience();
    }

    /**
     * @param authorizationHeader the raw {@code Authorization} header value (may be null)
     * @return the validation outcome
     */
    public Outcome authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Outcome.MISSING;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Outcome.MISSING;
        }

        try {
            // verifyWith() rejects unsecured ('none') tokens and verifies the HMAC.
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);

            // Pin the algorithm explicitly: verifyWith(SecretKey) would also accept a
            // token signed HS256/HS384 with the same secret, so enforce HS512 here.
            if (!REQUIRED_ALG.equals(jws.getHeader().getAlgorithm())) {
                return Outcome.INVALID;
            }

            Claims claims = jws.getPayload();
            if (!expectedIssuer.equals(claims.getIssuer())) {
                return Outcome.INVALID;
            }
            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                return Outcome.INVALID;
            }
            return Outcome.AUTHENTICATED;

        } catch (ExpiredJwtException e) {
            return Outcome.EXPIRED;
        } catch (JwtException | IllegalArgumentException e) {
            return Outcome.INVALID;
        }
    }
}
