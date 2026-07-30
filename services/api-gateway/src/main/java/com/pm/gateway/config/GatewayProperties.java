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
    private Limits limits = new Limits();
    private RateLimit rateLimit = new RateLimit();

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

    @Getter
    @Setter
    public static class Limits {
        /**
         * Maximum request body (bytes) the gateway will buffer and forward; a larger body is
         * rejected with 413 before it is read whole. This API is JSON-only (no file uploads),
         * so 2 MiB is generous; it caps a trivial memory-exhaustion vector at the edge.
         */
        private long maxBodyBytes = 2 * 1024 * 1024;
    }

    /**
     * Edge rate limiting. Complements — does not replace — the caddy-ratelimit rule in
     * docker/caddy/Caddyfile, which covers only the three token-minting auth endpoints and only
     * when traffic actually arrives through Caddy. This one applies to every proxied route and
     * travels with the application, so it still holds locally and if the gateway is ever reached
     * directly.
     *
     * <p>Two buckets, because they protect against different things:
     * <ul>
     *   <li><b>authenticated</b> — keyed on the userId from the verified token. Keying a logged-in
     *       user by IP would make everyone behind one CGNAT/office address throttle each other,
     *       which is a real risk for this user base.</li>
     *   <li><b>anonymous</b> — keyed on client IP, for the public auth routes where there is no
     *       identity yet. Tighter, since these are the endpoints worth brute-forcing.</li>
     * </ul>
     *
     * <p>Defaults are deliberately generous: a human clicking through the SPA never approaches
     * them, a script does immediately.
     */
    @Getter
    @Setter
    public static class RateLimit {
        /** Master switch. Off disables the check entirely (no Redis calls). */
        private boolean enabled = true;
        /** Requests allowed per window for a verified user. */
        private int authenticatedRequests = 300;
        /** Requests allowed per window for an unauthenticated caller (keyed by IP). */
        private int anonymousRequests = 30;
        /** Length of the fixed window, in seconds. */
        private int windowSeconds = 60;
    }
}
