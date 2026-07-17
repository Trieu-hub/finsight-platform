package com.pm.analyticsservice.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /**
     * RSA public key (base64 DER, PEM armor optional) auth-service's tokens are verified
     * with. With {@code jwksUri} set this is the bootstrap key only: enough to verify tokens
     * before the first JWK Set fetch succeeds, after which the fetched set takes over.
     */
    private String publicKey;

    /**
     * auth-service's JWK Set endpoint. Optional: unset pins this service to {@code publicKey}
     * and rotating a key then needs a restart. Set, it lets this service pick up a rotated key
     * on its own. NOT a per-request call -- the result is cached (see {@link JwtKeyResolver}).
     */
    private String jwksUri;
    /** Expected token issuer; enforced on validation (parity with the gateway). */
    private String issuer;
    /** Expected token audience; enforced on validation (parity with the gateway). */
    private String audience;
}
