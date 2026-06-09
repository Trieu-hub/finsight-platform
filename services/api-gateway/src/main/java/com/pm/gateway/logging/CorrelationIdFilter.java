package com.pm.gateway.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Edge correlation id. Establishes the id for a request at the gateway so it can be
 * carried through the whole call graph (gateway -> dashboard -> transaction/budget/user).
 *
 * <p>Reuses an incoming {@value #CORRELATION_ID_HEADER} when present; only generates a
 * new UUID when the header is missing or blank. An existing id is never overwritten. The
 * id is placed in the MDC (so gateway log lines, including downstream-failure warnings,
 * carry it), echoed on the response, and removed from the MDC on completion. The proxy
 * forwards the canonical id downstream from the MDC.
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
