package com.pm.dashboardservice.client;

import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.config.ResilienceConfig;
import com.pm.dashboardservice.exception.UpstreamException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the resilience layer itself (independent of HTTP/gRPC transport). */
class UpstreamCallsTest {

    private UpstreamCalls callsWith(DashboardProperties.Resilience r) {
        return new UpstreamCalls(
                CircuitBreakerRegistry.of(ResilienceConfig.circuitBreakerConfig(r)),
                RetryRegistry.of(ResilienceConfig.retryConfig(r)));
    }

    @Test
    void retriesTransientFailureThenSucceeds() {
        DashboardProperties.Resilience r = new DashboardProperties().getResilience();
        r.setRetryWaitDuration(Duration.ofMillis(1)); // keep the test fast
        UpstreamCalls calls = callsWith(r);           // maxAttempts defaults to 2
        AtomicInteger attempts = new AtomicInteger();

        String result = calls.call("svc", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new ResourceAccessException("connection refused");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2); // failed once, retried once, then succeeded
    }

    @Test
    void doesNotRetryClientError_andWrapsAsUpstreamException() {
        UpstreamCalls calls = callsWith(new DashboardProperties().getResilience());
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> calls.call("svc", () -> {
            attempts.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
        })).isInstanceOf(UpstreamException.class);

        assertThat(attempts).hasValue(1); // a 4xx is the caller's fault: not retried
    }

    @Test
    void opensBreakerAfterRepeatedFailures_thenShortCircuits() {
        DashboardProperties.Resilience r = new DashboardProperties().getResilience();
        r.setMaxAttempts(1);          // no retry: one action invocation per logical call
        r.setSlidingWindowSize(4);
        r.setMinimumNumberOfCalls(4);
        r.setFailureRateThreshold(50f);
        UpstreamCalls calls = callsWith(r);

        // Fill the window with failures — 100% failure rate opens the breaker.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> calls.call("svc", () -> {
                throw new ResourceAccessException("down");
            })).isInstanceOf(UpstreamException.class);
        }

        // The breaker is now OPEN: the next call is short-circuited — the action never runs and
        // the fail-fast CallNotPermittedException surfaces as UpstreamException.
        AtomicInteger invoked = new AtomicInteger();
        assertThatThrownBy(() -> calls.call("svc", () -> {
            invoked.incrementAndGet();
            return "unreached";
        }))
                .isInstanceOf(UpstreamException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(invoked).hasValue(0);
    }
}
