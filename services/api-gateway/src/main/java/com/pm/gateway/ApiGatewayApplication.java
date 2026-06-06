package com.pm.gateway;

import com.pm.gateway.config.GatewayProperties;
import com.pm.gateway.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * FinSight API Gateway — V1.
 *
 * <p>A servlet reverse proxy that forwards requests to the four backend services by
 * path prefix and relays their responses unchanged.
 *
 * <p>Phase 1: routing only. Phase 2 (this change): edge JWT authentication — the
 * gateway validates the access token (signature, HS512, issuer, audience) on every
 * non-public route and rejects bad tokens with the frozen auth error contract, while
 * forwarding the bearer token downstream. It still injects NO identity headers, does
 * NO rate limiting, and adds NO correlation IDs — those remain in later phases. The
 * contract is frozen in docs/ADR-0002.
 */
@SpringBootApplication
@EnableConfigurationProperties({GatewayProperties.class, JwtProperties.class})
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
