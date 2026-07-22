package com.pm.gateway.ratelimit;

import com.pm.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the window decision and the fail-open contract. The Lua script's own behaviour
 * belongs to Redis; what matters here is how its {@code {count, ttl}} reply is interpreted.
 */
class RateLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GatewayProperties properties = new GatewayProperties();

    private RateLimiter limiter() {
        return new RateLimiter(redisTemplate, properties, meterRegistry);
    }

    /** Stubs the script reply: the caller's count within the window, and the window's remaining TTL. */
    @SuppressWarnings("unchecked")
    private void redisReplies(long count, long ttl) {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), anyString()))
                .thenReturn(List.of(count, ttl));
    }

    private double rejected(String scope) {
        return meterRegistry.get("finsight.gateway.ratelimit.rejected")
                .tag("scope", scope).counter().count();
    }

    private double checkFailed() {
        return meterRegistry.get("finsight.gateway.ratelimit.check.failed").counter().count();
    }

    @Test
    void underTheLimit_isAllowed() {
        properties.getRateLimit().setAnonymousRequests(30);
        redisReplies(30, 45); // the 30th request of a 30-request window

        assertThat(limiter().check(RateLimiter.Scope.ANONYMOUS, "1.2.3.4").allowed()).isTrue();
    }

    @Test
    void overTheLimit_isRejectedWithTheRemainingWindowAsRetryAfter() {
        properties.getRateLimit().setAnonymousRequests(30);
        redisReplies(31, 45);

        RateLimiter.Decision decision = limiter().check(RateLimiter.Scope.ANONYMOUS, "1.2.3.4");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(45);
        assertThat(rejected("anonymous")).isEqualTo(1);
    }

    @Test
    void theTwoScopesHaveIndependentLimits() {
        properties.getRateLimit().setAnonymousRequests(30);
        properties.getRateLimit().setAuthenticatedRequests(300);
        redisReplies(100, 45); // over the anonymous limit, well under the authenticated one

        RateLimiter limiter = limiter();
        assertThat(limiter.check(RateLimiter.Scope.ANONYMOUS, "1.2.3.4").allowed()).isFalse();
        assertThat(limiter.check(RateLimiter.Scope.AUTHENTICATED, "7").allowed()).isTrue();
    }

    @Test
    void aMissingTtlFallsBackToTheFullWindow() {
        properties.getRateLimit().setAnonymousRequests(30);
        properties.getRateLimit().setWindowSeconds(60);
        redisReplies(31, -1); // key vanished between INCR and TTL

        assertThat(limiter().check(RateLimiter.Scope.ANONYMOUS, "1.2.3.4").retryAfterSeconds())
                .isEqualTo(60);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDown_failsOpenAndCounts() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThat(limiter().check(RateLimiter.Scope.AUTHENTICATED, "7").allowed()).isTrue();
        assertThat(checkFailed()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnexpectedScriptReply_failsOpenAndCounts() {
        when(redisTemplate.execute(any(RedisScript.class), any(List.class), anyString()))
                .thenReturn(null);

        assertThat(limiter().check(RateLimiter.Scope.ANONYMOUS, "1.2.3.4").allowed()).isTrue();
        assertThat(checkFailed()).isEqualTo(1);
    }

    @Test
    void disabled_allowsWithoutTouchingRedis() {
        properties.getRateLimit().setEnabled(false);

        assertThat(limiter().check(RateLimiter.Scope.ANONYMOUS, "1.2.3.4").allowed()).isTrue();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBlankIdentityIsNotCounted() {
        // An unresolvable key would put unrelated callers in one bucket; allow instead.
        assertThat(limiter().check(RateLimiter.Scope.AUTHENTICATED, null).allowed()).isTrue();
        assertThat(limiter().check(RateLimiter.Scope.ANONYMOUS, "  ").allowed()).isTrue();
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), anyString());
    }
}
