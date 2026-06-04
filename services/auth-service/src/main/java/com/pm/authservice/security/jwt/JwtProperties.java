package com.pm.authservice.security.jwt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    private String secret;
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
