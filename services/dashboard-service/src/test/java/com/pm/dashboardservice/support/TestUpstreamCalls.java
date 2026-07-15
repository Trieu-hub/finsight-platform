package com.pm.dashboardservice.support;

import com.pm.dashboardservice.client.UpstreamCalls;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.config.ResilienceConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

/**
 * Builds an {@link UpstreamCalls} with the production Resilience4j config (via
 * {@link ResilienceConfig}'s shared factories) for the client unit tests — so the tests exercise
 * the same breaker/retry behaviour the app runs with, without a Spring context. A fresh instance
 * (fresh registries, so isolated breaker state) is returned per call.
 */
public final class TestUpstreamCalls {

    private TestUpstreamCalls() {
    }

    public static UpstreamCalls create() {
        DashboardProperties.Resilience r = new DashboardProperties().getResilience();
        return new UpstreamCalls(
                CircuitBreakerRegistry.of(ResilienceConfig.circuitBreakerConfig(r)),
                RetryRegistry.of(ResilienceConfig.retryConfig(r)));
    }
}
