package com.pm.authservice.security.jwt;

import com.pm.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

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
        var builder = Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().getName().name())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration));

        // Issuer/audience are emitted so the token contract is in place ahead of a
        // gateway. They are NOT yet required on validation (see validateToken).
        if (issuer != null && !issuer.isBlank()) {
            builder.issuer(issuer);
        }
        if (audience != null && !audience.isBlank()) {
            builder.audience().add(audience).and();
        }

        return builder.signWith(signingKey).compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            // Non-enforced observation: surface mismatches/absences for monitoring so we
            // can confirm tokens carry the expected iss/aud before enforcement is enabled.
            observeIssuerAudience(claims);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Logs the observed issuer/audience against expectations. Never rejects a token. */
    private void observeIssuerAudience(Claims claims) {
        if (issuer != null && !issuer.isBlank() && !issuer.equals(claims.getIssuer())) {
            log.warn("JWT issuer mismatch (not enforced): expected='{}' actual='{}'",
                    issuer, claims.getIssuer());
        }
        Set<String> aud = claims.getAudience();
        if (audience != null && !audience.isBlank() && (aud == null || !aud.contains(audience))) {
            log.warn("JWT audience mismatch (not enforced): expected='{}' actual='{}'",
                    audience, aud);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
