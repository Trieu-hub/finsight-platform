package com.pm.gateway;

import com.pm.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * FinSight API Gateway — V1.
 *
 * <p>Phase 1 scope: a routing-only servlet reverse proxy. It forwards requests to
 * the four backend services by path prefix and returns their responses unchanged.
 * It performs NO authentication, NO rate limiting, and injects NO identity headers
 * yet — those are added additively in later phases. See docs/ADR-0001.
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
