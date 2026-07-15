package com.pm.dashboardservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Programmatic Resilience4j wiring for the BFF's upstream fan-out. Deliberately NOT the
 * {@code resilience4j-spring-boot} AOP starter: Boot 4 relocated a lot of autoconfiguration
 * (the tracing work already hit that), and the plain core libraries have no such coupling, so
 * this is the robust choice. Registries are shared by {@code UpstreamCalls}, which looks up one
 * circuit breaker + retry per upstream service name.
 *
 * <p>The circuit-breaker and retry state/call metrics are bound to the Micrometer registry, so
 * they appear on {@code /actuator/prometheus} — Grafana and the Alertmanager rules can see a
 * breaker trip ({@code resilience4j_circuitbreaker_state}).
 */
@Configuration
public class ResilienceConfig {

    /**
     * A failure that means the <b>upstream</b> is unhealthy (so it should count against the
     * breaker): connectivity/timeout, an HTTP 5xx, or a transient gRPC status. A 4xx / gRPC
     * client-error is the request's own fault and must NOT trip the breaker.
     */
    static boolean isUpstreamFailure(Throwable t) {
        if (t instanceof ResourceAccessException) {
            return true; // REST connect/read timeout or connection refused
        }
        if (t instanceof HttpServerErrorException) {
            return true; // REST 5xx
        }
        if (t instanceof StatusRuntimeException grpc) {
            Status.Code code = grpc.getStatus().getCode();
            return code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }

    /**
     * A failure worth <b>retrying</b>: a narrower set than the above. Only connection-level
     * blips (REST I/O errors / timeouts, gRPC UNAVAILABLE) are retried. A 5xx is left alone —
     * it may be a deterministic server error, and retrying only piles load onto a struggling
     * service; DEADLINE_EXCEEDED already consumed the full deadline, so a retry rarely helps.
     */
    static boolean isRetryable(Throwable t) {
        if (t instanceof ResourceAccessException) {
            return true;
        }
        if (t instanceof StatusRuntimeException grpc) {
            return grpc.getStatus().getCode() == Status.Code.UNAVAILABLE;
        }
        return false;
    }

    /** Circuit-breaker config, shared by beans and tests (single source of truth). */
    public static CircuitBreakerConfig circuitBreakerConfig(DashboardProperties.Resilience r) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(r.getFailureRateThreshold())
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(r.getSlidingWindowSize())
                .minimumNumberOfCalls(r.getMinimumNumberOfCalls())
                .waitDurationInOpenState(r.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(r.getPermittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(ResilienceConfig::isUpstreamFailure)
                .build();
    }

    /** Retry config, shared by beans and tests. */
    public static RetryConfig retryConfig(DashboardProperties.Resilience r) {
        return RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .waitDuration(r.getRetryWaitDuration())
                .retryOnException(ResilienceConfig::isRetryable)
                .build();
    }

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(DashboardProperties properties, MeterRegistry meterRegistry) {
        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(circuitBreakerConfig(properties.getResilience()));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    RetryRegistry retryRegistry(DashboardProperties properties, MeterRegistry meterRegistry) {
        RetryRegistry registry = RetryRegistry.of(retryConfig(properties.getResilience()));
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
        return registry;
    }
}
