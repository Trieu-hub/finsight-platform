package com.pm.authservice.security.jwt;

import com.pm.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Service
public class JwtService {

    /** Pinned signing algorithm; tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "HS512";

    private final SecretKey signingKey;
    private final long accessTokenExpiration;
    private final String issuer;
    private final String audience;

    public JwtService(JwtProperties jwtProperties) {
        this.signingKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = jwtProperties.getAccessTokenExpiration();
        this.issuer = jwtProperties.getIssuer();
        this.audience = jwtProperties.getAudience();
    }

    public String generateAccessToken(User user) {
        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().getName().name())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(signingKey)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Parses and fully validates the token, enforcing the frozen contract in one place
     * (parity with api-gateway / docs/ADR-0002): HMAC signature + expiration (via
     * {@code parseSignedClaims}), algorithm pinned to HS512, issuer {@code == finsight-auth},
     * audience contains {@code finsight-api}.
     */
    private Claims parseClaims(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token);

        // verifyWith(SecretKey) alone would also accept HS256/HS384 signed with the
        // same secret, so pin the algorithm explicitly.
        if (!REQUIRED_ALG.equals(jws.getHeader().getAlgorithm())) {
            throw new JwtException("Unexpected JWT algorithm: " + jws.getHeader().getAlgorithm());
        }

        Claims claims = jws.getPayload();
        if (!issuer.equals(claims.getIssuer())) {
            throw new JwtException("Unexpected JWT issuer: " + claims.getIssuer());
        }
        Set<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(audience)) {
            throw new JwtException("JWT audience does not contain " + audience);
        }
        return claims;
    }
}
