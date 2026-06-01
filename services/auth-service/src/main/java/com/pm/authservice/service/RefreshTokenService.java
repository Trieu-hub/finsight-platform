package com.pm.authservice.service;

import com.pm.authservice.entity.User;
import com.pm.authservice.security.jwt.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed refresh token store.
 * <p>
 * Two keys per session keep the existing "one active refresh token per user" semantics:
 * <ul>
 *     <li>{@code refresh:token:{token} -> userId} — lookup for refresh / logout</li>
 *     <li>{@code refresh:user:{userId} -> token} — lets us revoke the prior token on rotation / logout</li>
 * </ul>
 * Both keys share the refresh-token TTL, so expiry is handled natively by Redis
 * (no manual expiry-date check, no cleanup job).
 */
@Service
public class RefreshTokenService {

    private static final String TOKEN_KEY_PREFIX = "refresh:token:";
    private static final String USER_KEY_PREFIX = "refresh:user:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
        this.redisTemplate = redisTemplate;
        this.jwtProperties = jwtProperties;
    }

    /** Revokes any existing token for the user, then issues and stores a fresh one. */
    public String issue(User user) {
        revokeByUser(user.getId());

        String token = UUID.randomUUID().toString();
        Duration ttl = Duration.ofMillis(jwtProperties.getRefreshTokenExpiration());

        redisTemplate.opsForValue().set(tokenKey(token), String.valueOf(user.getId()), ttl);
        redisTemplate.opsForValue().set(userKey(user.getId()), token, ttl);

        return token;
    }

    /** Returns the owning user id, or empty if the token is unknown or expired (TTL elapsed). */
    public Optional<Long> findUserIdByToken(String token) {
        String userId = redisTemplate.opsForValue().get(tokenKey(token));
        return Optional.ofNullable(userId).map(Long::valueOf);
    }

    /** Removes the user's current token mapping in both directions. */
    public void revokeByUser(Long userId) {
        String currentToken = redisTemplate.opsForValue().get(userKey(userId));
        if (currentToken != null) {
            redisTemplate.delete(tokenKey(currentToken));
        }
        redisTemplate.delete(userKey(userId));
    }

    private String tokenKey(String token) {
        return TOKEN_KEY_PREFIX + token;
    }

    private String userKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }
}
