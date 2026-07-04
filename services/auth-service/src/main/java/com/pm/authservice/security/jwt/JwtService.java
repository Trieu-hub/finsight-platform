package com.pm.authservice.security.jwt;

import com.pm.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.Set;

@Service
public class JwtService {

    /** Pinned signing algorithm; tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "RS256";

    // Asymmetric signing (RS256): auth-service holds the private key and is the only
    // component that can mint tokens; every validator verifies with the public key alone.
    // This closes the shared-HMAC weakness where any service could also forge tokens.
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final long accessTokenExpiration;
    private final String issuer;
    private final String audience;

    public JwtService(JwtProperties jwtProperties) {
        this.privateKey = parsePrivateKey(jwtProperties.getPrivateKey());
        this.publicKey = parsePublicKey(jwtProperties.getPublicKey());
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
                .signWith(privateKey, Jwts.SIG.RS256)
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
     * (parity with api-gateway / docs/ADR-0002): RSA signature + expiration (via
     * {@code parseSignedClaims}), algorithm pinned to RS256, issuer {@code == finsight-auth},
     * audience contains {@code finsight-api}.
     */
    private Claims parseClaims(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token);

        // verifyWith(PublicKey) accepts the whole RSA family (RS256/384/512), so pin the
        // algorithm explicitly to the one auth-service issues.
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

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripArmor(pem));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA private key in jwt.private-key", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(stripArmor(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid RSA public key in jwt.public-key", e);
        }
    }

    /** Strips optional PEM armor and all whitespace, leaving the bare base64 DER body. */
    private static String stripArmor(String pem) {
        return pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
    }
}
