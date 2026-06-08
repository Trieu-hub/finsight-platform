package com.pm.userservice.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
    /** Expected token issuer; enforced on validation (parity with the gateway). */
    private String issuer;
    /** Expected token audience; enforced on validation (parity with the gateway). */
    private String audience;
}
