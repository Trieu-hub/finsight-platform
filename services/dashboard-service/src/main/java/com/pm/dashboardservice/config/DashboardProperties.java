package com.pm.dashboardservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Upstream service base URIs and outbound HTTP timeouts. Externalized so the targets
 * can be repointed per environment (localhost for local runs, compose DNS in Docker).
 */
@ConfigurationProperties(prefix = "dashboard")
@Getter
@Setter
public class DashboardProperties {

    private Services services = new Services();
    private Timeouts timeouts = new Timeouts();
    private Resilience resilience = new Resilience();

    @Getter
    @Setter
    public static class Services {
        // user-service is now called over gRPC (see GrpcClientConfig / spring.grpc.client);
        // only the REST upstreams keep a base URI here.
        private String transactionUri;
        private String budgetUri;
    }

    @Getter
    @Setter
    public static class Timeouts {
        /** Connection timeout for an upstream call (ms). */
        private long connectMs = 2000;
        /** Read timeout waiting on an upstream response (ms). */
        private long readMs = 5000;
    }

    /**
     * Circuit-breaker + retry tuning for the upstream fan-out (one breaker/retry per upstream
     * service). Defaults suit a small single-box demo; override per environment if needed.
     */
    @Getter
    @Setter
    public static class Resilience {
        /** Open the breaker once this percentage of recent calls have failed. */
        private float failureRateThreshold = 50f;
        /** Number of recent calls (count-based window) the failure rate is measured over. */
        private int slidingWindowSize = 10;
        /** Minimum calls recorded before the failure rate is evaluated (avoids tripping on the first blip). */
        private int minimumNumberOfCalls = 5;
        /** How long the breaker stays OPEN (short-circuiting) before it half-opens to probe. */
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        /** Probe calls allowed while HALF_OPEN before deciding to close or re-open. */
        private int permittedCallsInHalfOpenState = 3;
        /** Total attempts per logical call (1 = no retry). Retries apply only to transient failures. */
        private int maxAttempts = 2;
        /** Fixed backoff between retry attempts. */
        private Duration retryWaitDuration = Duration.ofMillis(200);
    }
}
