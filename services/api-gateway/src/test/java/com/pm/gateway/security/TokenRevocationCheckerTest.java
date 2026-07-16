package com.pm.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for the denylist lookup: the cutoff comparison and the fail-open contract. */
class TokenRevocationCheckerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TokenRevocationChecker checker =
            new TokenRevocationChecker(redisTemplate, meterRegistry);

    /** A token for user 7 issued at the given epoch second. */
    private static Claims claims(long issuedAtSeconds) {
        return new DefaultClaims(Map.of(
                "userId", 7,
                Claims.ISSUED_AT, new Date(issuedAtSeconds * 1000)));
    }

    private void cutoff(String value) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("revoked:user:7")).thenReturn(value);
    }

    private double failedCount() {
        return meterRegistry.get("finsight.gateway.revocation.check.failed").counter().count();
    }

    @Test
    void noCutoffForUser_isNotRevoked() {
        cutoff(null);
        assertThat(checker.isRevoked(claims(1000))).isFalse();
    }

    @Test
    void tokenIssuedBeforeCutoff_isRevoked() {
        cutoff("1000");
        assertThat(checker.isRevoked(claims(999))).isTrue();
    }

    @Test
    void tokenIssuedAtOrAfterCutoff_survives() {
        cutoff("1000");
        // The writer rounds the cutoff up to the next second precisely so that iat == cutoff
        // means "minted after the revocation" — a fresh login must not be killed by it.
        assertThat(checker.isRevoked(claims(1000))).isFalse();
        assertThat(checker.isRevoked(claims(1001))).isFalse();
    }

    @Test
    void redisDown_failsOpenAndCounts() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(checker.isRevoked(claims(999))).isFalse();
        assertThat(failedCount()).isEqualTo(1);
    }

    @Test
    void malformedCutoff_failsOpenAndCounts() {
        cutoff("not-a-number");
        assertThat(checker.isRevoked(claims(999))).isFalse();
        assertThat(failedCount()).isEqualTo(1);
    }

    @Test
    void claimsWithoutUserIdOrIssuedAt_areNotRevoked_andNeverHitRedis() {
        assertThat(checker.isRevoked(new DefaultClaims(Map.of()))).isFalse();
        assertThat(checker.isRevoked(new DefaultClaims(Map.of("userId", 7)))).isFalse();
        // redisTemplate is a strict-free mock with no opsForValue() stubbing here: had the
        // checker looked anything up, it would have NPE'd rather than silently passed.
    }
}
