package com.pm.gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Externalized routing contract. The route table and timeouts are configuration,
 * never hardcoded, so targets can be repointed per environment (local vs compose)
 * without a code change. See application.yml.
 */
@ConfigurationProperties(prefix = "gateway")
@Getter
@Setter
public class GatewayProperties {

    /** Ordered prefix → target routes. First matching prefix wins. */
    private List<Route> routes = new ArrayList<>();

    private Timeouts timeouts = new Timeouts();

    @Getter
    @Setter
    public static class Route {
        /** Public path prefix, e.g. {@code /api/v1/budgets}. */
        private String prefix;
        /** Internal target base URI, e.g. {@code http://budget-service:8084}. */
        private String uri;
    }

    @Getter
    @Setter
    public static class Timeouts {
        /** Connection timeout to a downstream service (ms). */
        private long connectMs = 2000;
        /** Read timeout waiting on a downstream response (ms). */
        private long readMs = 10000;
    }
}
