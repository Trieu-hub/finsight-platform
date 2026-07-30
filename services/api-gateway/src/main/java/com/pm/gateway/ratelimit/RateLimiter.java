package com.pm.gateway.ratelimit;

import com.pm.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Edge rate limiting, counted in Redis.
 *
 * <p>A fixed window per key: the first request of a window creates the counter and sets its TTL,
 * every later one increments it, and the counter disappears on expiry. Fixed window rather than a
 * sliding log or token bucket because the whole state is one integer with a TTL — Redis already
 * does exactly that natively, and the burst it permits at a window boundary (up to 2× the limit
 * across two adjacent windows) is irrelevant at the thresholds this runs at.
 *
 * <p><b>Atomicity.</b> INCR and EXPIRE run in one Lua script. Done as two round trips, a process
 * dying between them leaves a counter with no TTL — which never resets, so the caller is blocked
 * permanently once it reaches the limit. The script also returns the remaining TTL so a rejected
 * request gets an accurate {@code Retry-After} without a second round trip.
 *
 * <p><b>Fails open</b>, matching {@link com.pm.gateway.security.TokenRevocationChecker}: if Redis
 * is unreachable the request is allowed and the failure is counted
 * ({@code finsight.gateway.ratelimit.check.failed}). Rate limiting is abuse control, not
 * correctness — a Redis outage must not take the whole API down. Caddy's own limiter still covers
 * the auth endpoints meanwhile, and the counter is alertable.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final String KEY_PREFIX = "ratelimit:";

    /**
     * Returns {@code {count, ttlSeconds}}. EXPIRE is set only on the first hit of a window so a
     * steady stream of requests cannot keep pushing the window out and make it never reset.
     */
    private static final RedisScript<List> INCREMENT_AND_EXPIRE = new DefaultRedisScript<>("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return {n, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties.RateLimit config;
    private final Counter checkFailedCounter;
    private final Counter rejectedAuthenticated;
    private final Counter rejectedAnonymous;

    public RateLimiter(StringRedisTemplate redisTemplate, GatewayProperties properties,
                       MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.config = properties.getRateLimit();
        this.checkFailedCounter = Counter.builder("finsight.gateway.ratelimit.check.failed")
                .description("Rate-limit checks that could not reach Redis; the request was allowed through")
                .register(meterRegistry);
        this.rejectedAuthenticated = Counter.builder("finsight.gateway.ratelimit.rejected")
                .description("Requests rejected with 429 by the edge rate limiter")
                .tag("scope", "authenticated")
                .register(meterRegistry);
        this.rejectedAnonymous = Counter.builder("finsight.gateway.ratelimit.rejected")
                .description("Requests rejected with 429 by the edge rate limiter")
                .tag("scope", "anonymous")
                .register(meterRegistry);
    }

    /** Which bucket a request is counted against, and how it is keyed. */
    public enum Scope {
        /** Keyed on the userId from the verified token. */
        AUTHENTICATED,
        /** Keyed on client IP — the public routes, where there is no identity yet. */
        ANONYMOUS
    }

    /**
     * The outcome of one check.
     *
     * @param allowed          false when the caller has exhausted its window
     * @param retryAfterSeconds seconds until the window resets; meaningful only when rejected
     */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    /**
     * Counts one request against {@code identity} and decides whether it may proceed.
     *
     * @param scope    which limit applies
     * @param identity the userId (AUTHENTICATED) or client IP (ANONYMOUS)
     */
    public Decision check(Scope scope, String identity) {
        if (!config.isEnabled() || identity == null || identity.isBlank()) {
            return Decision.ALLOWED;
        }

        int limit = scope == Scope.AUTHENTICATED
                ? config.getAuthenticatedRequests()
                : config.getAnonymousRequests();
        String key = KEY_PREFIX + (scope == Scope.AUTHENTICATED ? "user:" : "ip:") + identity;

        List<?> result;
        try {
            result = redisTemplate.execute(INCREMENT_AND_EXPIRE, List.of(key),
                    String.valueOf(config.getWindowSeconds()));
        } catch (RuntimeException e) {
            checkFailedCounter.increment();
            log.error("Rate-limit check failed for {}; allowing the request through "
                    + "(no limit is being enforced while Redis is unreachable)", key, e);
            return Decision.ALLOWED;
        }
        if (result == null || result.size() < 2) {
            checkFailedCounter.increment();
            log.error("Rate-limit script returned {} for {}; allowing the request through", result, key);
            return Decision.ALLOWED;
        }

        long count = ((Number) result.get(0)).longValue();
        if (count <= limit) {
            return Decision.ALLOWED;
        }

        long ttl = ((Number) result.get(1)).longValue();
        (scope == Scope.AUTHENTICATED ? rejectedAuthenticated : rejectedAnonymous).increment();
        // A missing/negative TTL should not become a nonsensical Retry-After; fall back to the
        // full window, which is the longest the caller could possibly have to wait.
        return new Decision(false, ttl > 0 ? ttl : config.getWindowSeconds());
    }
}
