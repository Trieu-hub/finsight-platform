package com.pm.gateway.security;

import com.pm.gateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Edge JWT validation (Phase 2). Verifies the access token's signature and enforces
 * the frozen contract (docs/ADR-0002): algorithm pinned to <b>RS256</b>, issuer
 * {@code == finsight-auth}, audience set contains {@code finsight-api}.
 *
 * <p>Verification uses auth-service's RSA <b>public</b> keys only; the gateway cannot
 * mint tokens. Which key is chosen per token is {@link JwtKeyResolver}'s job, via the
 * {@code kid} header and auth-service's JWK Set. This validation is additive: the gateway rejects bad tokens early, but
 * every downstream service still validates the token itself (the V1 invariant — the
 * gateway is removable without service changes). The bearer token is therefore
 * forwarded unchanged by the proxy.
 *
 * <p>Maps failures to the three frozen authentication outcomes so the proxy can emit
 * the correct error code:
 * <ul>
 *   <li>{@link Outcome#MISSING} — no / malformed {@code Authorization: Bearer} header.</li>
 *   <li>{@link Outcome#EXPIRED} — signature valid but {@code exp} is in the past.</li>
 *   <li>{@link Outcome#INVALID} — bad signature, wrong {@code alg}, failed
 *       {@code iss}/{@code aud}, or otherwise unparseable.</li>
 *   <li>{@link Outcome#REVOKED} — token is valid but was revoked (logout / ban / role change);
 *       see {@link TokenRevocationChecker}.</li>
 * </ul>
 */
@Component
public class JwtAuthenticator {

    /** The pinned signing algorithm. Tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "RS256";
    private static final String BEARER_PREFIX = "Bearer ";

    public enum Outcome { AUTHENTICATED, MISSING, EXPIRED, INVALID, REVOKED }

    /**
     * Validation outcome plus the caller's identity.
     *
     * <p>{@code userId} is populated only when {@code outcome == AUTHENTICATED}, and comes from the
     * claims of an already signature-verified token — so it is safe to key the rate limiter on
     * (unlike anything a client can set). Null on every failure path.
     */
    public record Result(Outcome outcome, String userId) {
        private static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private final JwtKeyResolver keyResolver;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final TokenRevocationChecker revocationChecker;

    public JwtAuthenticator(JwtProperties properties, JwtKeyResolver keyResolver,
                            TokenRevocationChecker revocationChecker) {
        this.keyResolver = keyResolver;
        this.expectedIssuer = properties.getIssuer();
        this.expectedAudience = properties.getAudience();
        this.revocationChecker = revocationChecker;
    }

    /**
     * @param authorizationHeader the raw {@code Authorization} header value (may be null)
     * @return the validation outcome, with the userId when it is AUTHENTICATED
     */
    public Result authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Result.of(Outcome.MISSING);
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Result.of(Outcome.MISSING);
        }

        try {
            // The key comes from the token's own kid via the JWK Set (see JwtKeyResolver), not
            // from a single key pinned at startup — that is what allows a key to be rotated
            // without restarting this validator. parseSignedClaims() still demands a *signed*
            // JWT, so an unsecured ('none') token is rejected before any key is consulted, and
            // an unknown kid resolves to no key and fails the same way.
            Jws<Claims> jws = Jwts.parser()
                    .keyLocator(keyResolver)
                    .build()
                    .parseSignedClaims(token);

            // Pin the algorithm explicitly: an RSA public key would also verify a token signed
            // RS384/RS512, so enforce RS256 here.
            if (!REQUIRED_ALG.equals(jws.getHeader().getAlgorithm())) {
                return Result.of(Outcome.INVALID);
            }

            Claims claims = jws.getPayload();
            if (!expectedIssuer.equals(claims.getIssuer())) {
                return Result.of(Outcome.INVALID);
            }
            Set<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(expectedAudience)) {
                return Result.of(Outcome.INVALID);
            }

            // Last: the token is authentic, but may have been revoked since it was minted.
            // Checked only once the cheap, local checks pass, so a bogus token never costs a
            // Redis round-trip.
            if (revocationChecker.isRevoked(claims)) {
                return Result.of(Outcome.REVOKED);
            }

            // Same claim the revocation denylist is keyed on. Absent only on a token this
            // gateway did not mint the shape of; the rate limiter then falls back to IP.
            Object userId = claims.get("userId");
            return new Result(Outcome.AUTHENTICATED, userId == null ? null : String.valueOf(userId));

        } catch (ExpiredJwtException e) {
            return Result.of(Outcome.EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            return Result.of(Outcome.INVALID);
        }
    }

}
