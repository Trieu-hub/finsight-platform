package com.pm.gateway.proxy;

import com.pm.gateway.config.GatewayProperties;
import com.pm.gateway.logging.CorrelationIdFilter;
import com.pm.gateway.security.JwtAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 1 routing-only reverse proxy.
 *
 * <p>Catch-all controller: matches every request, resolves the target service by
 * path prefix, enforces edge authentication on non-public routes (Phase 2), then
 * forwards method + headers + body unchanged and relays the downstream status/headers/
 * body back to the caller. The bearer token is forwarded downstream (services still
 * validate it themselves). No header injection, no rate limiting — those are added in
 * later phases. Actuator endpoints are served by their own higher-precedence handler
 * mapping and never reach here.
 */
@RestController
public class GatewayProxyController {

    private static final Logger log = LoggerFactory.getLogger(GatewayProxyController.class);

    private static final String AUTHORIZATION = "Authorization";

    /**
     * Hop-by-hop headers must not be forwarded (RFC 7230 §6.1). Content-Length and
     * Transfer-Encoding are also dropped so the servlet container recomputes them
     * for the relayed body.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade",
            "content-length", "host");

    private final GatewayProperties properties;
    private final RestClient client;
    private final JwtAuthenticator authenticator;

    public GatewayProxyController(GatewayProperties properties, RestClient client,
                                  JwtAuthenticator authenticator) {
        this.properties = properties;
        this.client = client;
        this.authenticator = authenticator;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI();

        GatewayProperties.Route route = resolve(path);
        if (route == null) {
            return error(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "No route matches " + path);
        }

        // Phase 2: enforce edge authentication on every non-public route. The token is
        // still forwarded downstream below (services keep validating it themselves).
        if (!isPublic(request.getMethod(), path)) {
            ResponseEntity<byte[]> authError = authenticate(request);
            if (authError != null) {
                return authError;
            }
        }

        URI target = buildTargetUri(route, path, request.getQueryString());
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());

        RestClient.RequestBodySpec spec = client.method(method).uri(target);
        copyRequestHeaders(request, spec);
        // Forward the canonical correlation id (established by CorrelationIdFilter, which
        // reused an incoming one or generated it) so a single id spans the call graph.
        // The inbound copy of this header is skipped below to avoid a duplicate.
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            spec.header(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
        if (body.length > 0) {
            spec.body(body);
        }

        try {
            // exchange() gives raw access and does NOT throw on 4xx/5xx, so downstream
            // error responses are relayed verbatim rather than swallowed.
            return spec.exchange((req, res) -> {
                byte[] responseBody = StreamUtils.copyToByteArray(res.getBody());
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(res.getStatusCode());
                copyResponseHeaders(res.getHeaders(), builder);
                return builder.body(responseBody);
            }, false);
        } catch (ResourceAccessException e) {
            // Connectivity / timeout failures to the downstream service.
            if (isTimeout(e)) {
                log.warn("Downstream timeout: {} {} -> {}", method, path, target, e);
                return error(HttpStatus.GATEWAY_TIMEOUT, "SERVICE_TIMEOUT",
                        "Downstream service did not respond in time");
            }
            log.warn("Downstream unreachable: {} {} -> {}", method, path, target, e);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Downstream service is unavailable");
        }
    }

    /**
     * Validates the bearer token and maps a failure to the frozen auth error contract
     * (docs/ADR-0002 §5). Returns {@code null} when the request is authenticated and may
     * proceed.
     */
    private ResponseEntity<byte[]> authenticate(HttpServletRequest request) {
        JwtAuthenticator.Outcome outcome = authenticator.authenticate(request.getHeader(AUTHORIZATION));
        return switch (outcome) {
            case AUTHENTICATED -> null;
            case MISSING -> error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Authentication required");
            case EXPIRED -> error(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED",
                    "Access token has expired");
            case INVALID -> error(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID",
                    "Access token is invalid");
        };
    }

    /** True if the (method, path) pair is on the frozen public allow-list (exact match). */
    private boolean isPublic(String method, String path) {
        for (GatewayProperties.PublicRoute pr : properties.getPublicRoutes()) {
            if (pr.getMethod().equalsIgnoreCase(method) && pr.getPath().equals(path)) {
                return true;
            }
        }
        return false;
    }

    /** First matching prefix wins. */
    private GatewayProperties.Route resolve(String path) {
        for (GatewayProperties.Route route : properties.getRoutes()) {
            String prefix = route.getPrefix();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return route;
            }
        }
        return null;
    }

    private URI buildTargetUri(GatewayProperties.Route route, String path, String query) {
        String base = route.getUri();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + path + (query != null ? "?" + query : "");
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid target URI: " + url, e);
        }
    }

    private void copyRequestHeaders(HttpServletRequest request, RestClient.RequestBodySpec spec) {
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            // Skip the inbound correlation header: proxy() re-adds the canonical id from
            // the MDC, so copying it here too would forward a duplicate.
            if (name.equalsIgnoreCase(CorrelationIdFilter.CORRELATION_ID_HEADER)) {
                continue;
            }
            var values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                spec.header(name, values.nextElement());
            }
        }
    }

    private void copyResponseHeaders(HttpHeaders downstream, ResponseEntity.BodyBuilder builder) {
        downstream.forEach((name, values) -> {
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                builder.header(name, value);
            }
        });
    }

    private boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException
                    || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the gateway error envelope by hand. Jackson is not on the routing-phase
     * classpath, and the values here are gateway-controlled, so a tiny JSON string
     * (with conservative escaping) is sufficient and dependency-free.
     */
    private ResponseEntity<byte[]> error(HttpStatus status, String code, String message) {
        String json = "{\"success\":false,\"error\":{\"code\":\"" + escape(code)
                + "\",\"message\":\"" + escape(message) + "\"}}";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
