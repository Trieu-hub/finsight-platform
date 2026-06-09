package com.pm.dashboardservice.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a correlation id into the SLF4J MDC for the lifetime of each request so every
 * log line — normal and error-path alike — can be tied back to one request as it flows
 * across services.
 *
 * <p>Reuses an incoming {@value #CORRELATION_ID_HEADER} when present; only generates a
 * new UUID when the header is missing or blank. An existing id is never overwritten.
 * The id is echoed on the response and removed from the MDC on completion (so it cannot
 * leak onto a pooled thread's next request). The same id is relayed to upstream services
 * by the RestClient interceptor configured in {@code RestClientConfig}.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Single source of the header name — do not repeat this raw string elsewhere. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /** MDC key under which the correlation id is stored. */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }
}
