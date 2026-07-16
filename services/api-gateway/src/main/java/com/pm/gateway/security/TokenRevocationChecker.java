package com.pm.gateway.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Enforces the access-token denylist auth-service writes (its {@code TokenRevocationService}).
 * <p>
 * A valid signature only proves a token was minted by auth-service — not that it is still
 * meant to work. Logout, ban, role change and delete all record a per-user cutoff in Redis;
 * any token issued before it is rejected here, at the edge, rather than living out its TTL.
 * <p>
 * Read-only: the gateway never writes the denylist. Keys and semantics are owned by
 * auth-service and must stay in step with it.
 * <p>
 * <b>Fails open.</b> If Redis is unreachable the request is allowed through and the failure is
 * logged and counted ({@code finsight.gateway.revocation.check.failed}). This is deliberate:
 * revocation is a second layer of defence over an already-signature-verified, short-lived
 * (15 min) token, so a Redis outage should not take the whole API down with it. The tradeoff is
 * that revocation does not bite while Redis is down — hence the counter, which is alertable.
 */
@Component
public class TokenRevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationChecker.class);
    private static final String KEY_PREFIX = "revoked:user:";

    private final StringRedisTemplate redisTemplate;
    private final Counter checkFailedCounter;

    public TokenRevocationChecker(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.checkFailedCounter = Counter.builder("finsight.gateway.revocation.check.failed")
                .description("Revocation lookups that could not reach Redis; the request was allowed through")
                .register(meterRegistry);
    }

    /**
     * @param claims the already-verified claims of the presented access token
     * @return true if the token has been revoked and must be rejected
     */
    public boolean isRevoked(Claims claims) {
        Object userId = claims.get("userId");
        Date issuedAt = claims.getIssuedAt();
        if (userId == null || issuedAt == null) {
            // Every token auth-service mints carries both. One that does not cannot be matched
            // against the denylist, so it cannot be shown to be revoked; signature, issuer,
            // audience and expiry have already been enforced by the caller.
            return false;
        }

        String cutoff;
        try {
            cutoff = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            checkFailedCounter.increment();
            log.error("Revocation check failed for userId={}; allowing the request through "
                    + "(revocation is not being enforced while Redis is unreachable)", userId, e);
            return false;
        }
        if (cutoff == null) {
            return false; // nothing revoked for this user, or the cutoff has aged out
        }

        try {
            // iat has one-second resolution; the cutoff is stored in the same unit, rounded up
            // by the writer so a token minted in the same second as the revocation is caught.
            return issuedAt.getTime() / 1000 < Long.parseLong(cutoff);
        } catch (NumberFormatException e) {
            checkFailedCounter.increment();
            log.error("Malformed revocation cutoff '{}' for userId={}; allowing the request through",
                    cutoff, userId, e);
            return false;
        }
    }
}
