package com.pm.authservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Redis-backed brute-force / credential-stuffing guard for login, scoped to a single
 * <em>account</em> (keyed by email). This is intentionally application-aware: a future
 * API Gateway can throttle by IP/route at the edge, but only the auth service knows
 * which identity is failing — so per-account lockout lives here and stays here.
 *
 * <p>Two keys per email:
 * <ul>
 *     <li>{@code login:attempts:{email}} — failure counter, expires after the attempt window</li>
 *     <li>{@code login:lock:{email}} — presence means locked, expires after the lock duration</li>
 * </ul>
 * Redis TTLs handle expiry natively (no cleanup job). Email is lower-cased so case
 * variants share one bucket.
 */
@Service
public class LoginAttemptService {

    private static final String ATTEMPT_PREFIX = "login:attempts:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate redisTemplate;
    private final int maxAttempts;
    private final Duration attemptWindow;
    private final Duration lockDuration;

    public LoginAttemptService(StringRedisTemplate redisTemplate,
                               @Value("${security.login.max-attempts:5}") int maxAttempts,
                               @Value("${security.login.attempt-window:15m}") Duration attemptWindow,
                               @Value("${security.login.lock-duration:15m}") Duration lockDuration) {
        this.redisTemplate = redisTemplate;
        this.maxAttempts = maxAttempts;
        this.attemptWindow = attemptWindow;
        this.lockDuration = lockDuration;
    }

    /** True while the account is locked out (recent failures exceeded the threshold). */
    public boolean isLocked(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockKey(email)));
    }

    /**
     * Records a failed login. The first failure starts the window; once failures reach
     * the threshold the account is locked for {@code lockDuration} and the counter cleared.
     */
    public void recordFailure(String email) {
        String key = attemptKey(email);
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts == null) {
            return;
        }
        if (attempts == 1L) {
            redisTemplate.expire(key, attemptWindow);
        }
        if (attempts >= maxAttempts) {
            redisTemplate.opsForValue().set(lockKey(email), "1", lockDuration);
            redisTemplate.delete(key);
        }
    }

    /** Clears counter and lock after a successful authentication. */
    public void reset(String email) {
        redisTemplate.delete(attemptKey(email));
        redisTemplate.delete(lockKey(email));
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String attemptKey(String email) {
        return ATTEMPT_PREFIX + normalize(email);
    }

    private String lockKey(String email) {
        return LOCK_PREFIX + normalize(email);
    }
}
