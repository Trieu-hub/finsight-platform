package com.pm.authservice.service;

import com.pm.authservice.security.jwt.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed access-token revocation.
 * <p>
 * Access tokens are self-contained JWTs: once minted, nothing can stop a validator from
 * accepting one until its {@code exp} passes. Revoking the refresh token alone therefore
 * left a window (the access-token TTL, 15 min by default) in which a logged-out, banned or
 * demoted user still held a working token. This closes that window.
 * <p>
 * One key per user records a <b>cutoff</b>:
 * <pre>{@code revoked:user:{userId} -> cutoff (epoch seconds)}</pre>
 * Every access token the user holds with {@code iat < cutoff} is considered revoked. A single
 * key thus kills <em>all</em> of a user's outstanding tokens at once — which is what logout,
 * ban, role change and delete all actually mean — without tracking individual tokens.
 * <p>
 * The key's TTL is the access-token lifetime: once that has elapsed, every token predating the
 * cutoff has expired on its own and the entry has nothing left to say. Expiry is handled
 * natively by Redis (no cleanup job), the same as {@link RefreshTokenService}.
 * <p>
 * Enforcement lives at api-gateway, the single entry point; see its {@code TokenRevocationChecker}.
 */
@Service
public class TokenRevocationService {

    private static final String KEY_PREFIX = "revoked:user:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public TokenRevocationService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMillis(jwtProperties.getAccessTokenExpiration());
    }

    /**
     * Revokes every access token currently held by the user.
     * <p>
     * The cutoff is rounded <b>up</b> to the next whole second. A JWT's {@code iat} has
     * one-second resolution, so a token minted earlier in the same wall-clock second as this
     * call carries an {@code iat} equal to the current second; rounding up ensures it falls
     * below the cutoff and is revoked too. The rounding therefore errs toward revoking one
     * second too much rather than one second too little — a user who logs in within the same
     * second as logging out simply logs in again, whereas the opposite error would leave a
     * revoked token valid for its full remaining lifetime.
     */
    public void revokeAllForUser(Long userId) {
        long cutoffSeconds = System.currentTimeMillis() / 1000 + 1;
        redisTemplate.opsForValue().set(key(userId), String.valueOf(cutoffSeconds), ttl);
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
