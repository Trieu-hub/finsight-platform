package com.pm.notificationservice.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /** RSA public key (base64 DER, PEM armor optional) auth-service's tokens are verified with. */
    private String publicKey;
    /** Expected token issuer; enforced on validation (parity with the gateway). */
    private String issuer;
    /** Expected token audience; enforced on validation (parity with the gateway). */
    private String audience;
}
