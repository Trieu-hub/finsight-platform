package com.pm.authservice.security.jwt;

import com.pm.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.ProtectedHeader;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.util.Date;
import java.util.Set;

@Service
public class JwtService {

    /** Pinned signing algorithm; tokens using any other {@code alg} are rejected. */
    private static final String REQUIRED_ALG = "RS256";

    // Asymmetric signing (RS256): auth-service holds the private key and is the only
    // component that can mint tokens; every validator verifies with the public key alone.
    // This closes the shared-HMAC weakness where any service could also forge tokens.
    private final JwtKeyRegistry keys;
    private final long accessTokenExpiration;
    private final String issuer;
    private final String audience;

    public JwtService(JwtKeyRegistry keys, JwtProperties jwtProperties) {
        this.keys = keys;
        this.accessTokenExpiration = jwtProperties.getAccessTokenExpiration();
        this.issuer = jwtProperties.getIssuer();
        this.audience = jwtProperties.getAudience();
    }

    public String generateAccessToken(User user) {
        return Jwts.builder()
                // Names the key this token was signed with, so a validator can pick the right
                // one out of the JWKS instead of being pinned to a single key for life. This
                // is what makes rotation possible without restarting every validator.
                .header().keyId(keys.signingKeyId()).and()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().getName().name())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(keys.signingKey(), Jwts.SIG.RS256)
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
                .keyLocator(this::locateKey)
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

    /**
     * Picks the verification key named by the token's {@code kid}. Returning {@code null} for
     * an unknown one makes jjwt reject the token, which is the point: a key dropped from the
     * registry at the end of a rotation stops being honoured immediately.
     */
    private Key locateKey(Header header) {
        String keyId = header instanceof ProtectedHeader protectedHeader
                ? protectedHeader.getKeyId()
                : null;
        // A token with no kid predates rotation support; it can only have been signed by the
        // key that was active when it was minted, which is the active key of this deployment.
        return keyId == null
                ? keys.verificationKeys().get(keys.signingKeyId())
                : keys.verificationKeys().get(keyId);
    }
}
