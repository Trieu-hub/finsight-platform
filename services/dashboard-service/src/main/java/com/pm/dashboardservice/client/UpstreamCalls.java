package com.pm.dashboardservice.client;

import com.pm.dashboardservice.exception.UpstreamException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/**
 * Runs an upstream call guarded by a per-service circuit breaker and retry. This is the single
 * choke point every BFF client routes through, so resilience and the {@code UpstreamException}
 * &rarr; 502 mapping live in one place.
 *
 * <p>Decoration order is <b>circuit breaker (outer) over retry (inner)</b>: transient blips are
 * retried within one logical call, the breaker records only the final outcome, and once the
 * breaker is OPEN the call is short-circuited immediately — no retry, no network hit. Every
 * failure (a call error, or a fail-fast {@link CallNotPermittedException} while OPEN) is
 * normalised to {@link UpstreamException}, which the global handler maps to 502.
 */
@Component
public class UpstreamCalls {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public UpstreamCalls(CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    /**
     * @param service the upstream's name (e.g. {@code "transaction-service"}); keys the breaker,
     *                the retry, and the exported metrics
     * @param action  the raw upstream call
     */
    public <T> T call(String service, Supplier<T> action) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(service);
        Retry retry = retryRegistry.retry(service);
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, action));
        try {
            return guarded.get();
        } catch (CallNotPermittedException | RestClientException | StatusRuntimeException e) {
            throw new UpstreamException(service, e);
        }
    }
}
