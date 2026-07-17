package com.pm.authservice.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * RSA keys as base64-encoded DER (PEM armor optional; whitespace tolerated).
     * auth-service is the ONLY holder of the private key — it alone can mint tokens.
     * The public key is used here to validate tokens on this service's own protected
     * endpoints (the admin console); every other service is given only the public key.
     */
    private String privateKey;
    private String publicKey;

    /**
     * Public keys that are no longer signing but must still verify — the rotation overlap
     * window (see {@link JwtKeyRegistry}). Published in the JWKS alongside the active key,
     * so validators keep honouring tokens minted just before a rotation. Normally empty;
     * populated only while a rotation is in flight, then cleared.
     * <p>Comma-separated in the environment, which is unambiguous: base64 has no comma.
     */
    private List<String> previousPublicKeys = new ArrayList<>();

    private long accessTokenExpiration;
    private long refreshTokenExpiration;

    /**
     * Token issuer/audience. Currently emitted into issued tokens and observed
     * (logged) on validation, but NOT enforced — enforcement is deferred until an
     * API Gateway / shared validation layer exists.
     */
    private String issuer;
    private String audience;
}
