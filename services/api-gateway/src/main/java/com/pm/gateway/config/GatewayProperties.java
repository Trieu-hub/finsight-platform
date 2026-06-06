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

    /**
     * Routes that bypass edge authentication (Phase 2). Frozen in docs/ADR-0002 §4:
     * the auth entry points that mint/rotate tokens. Matched by exact method + path.
     * (Actuator health/info are public by virtue of never reaching the proxy — they
     * are served by the gateway's own handler mapping — so they are not listed here.)
     */
    private List<PublicRoute> publicRoutes = new ArrayList<>();

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
    public static class PublicRoute {
        /** HTTP method, e.g. {@code POST}. */
        private String method;
        /** Exact public path, e.g. {@code /api/v1/auth/login}. */
        private String path;
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
