package com.pm.gateway.proxy;

import com.pm.gateway.config.GatewayProperties;
import com.pm.gateway.logging.CorrelationIdFilter;
import com.pm.gateway.ratelimit.ClientIpResolver;
import com.pm.gateway.ratelimit.RateLimiter;
import com.pm.gateway.security.JwtAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * path prefix, enforces edge authentication on non-public routes (Phase 2) and the edge
 * rate limit, then forwards method + headers + body unchanged and relays the downstream
 * status/headers/body back to the caller. The bearer token is forwarded downstream
 * (services still validate it themselves). No header injection. Actuator endpoints are
 * served by their own higher-precedence handler mapping and never reach here — so they
 * are neither authenticated nor rate-limited by this controller.
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
    private final java.net.http.HttpClient streamingClient;
    private final JwtAuthenticator authenticator;
    private final RateLimiter rateLimiter;

    public GatewayProxyController(GatewayProperties properties, RestClient client,
                                  java.net.http.HttpClient streamingClient,
                                  JwtAuthenticator authenticator,
                                  RateLimiter rateLimiter) {
        this.properties = properties;
        this.client = client;
        this.streamingClient = streamingClient;
        this.authenticator = authenticator;
        this.rateLimiter = rateLimiter;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI();

        GatewayProperties.Route route = resolve(path);
        if (route == null) {
            return error(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "No route matches " + path);
        }

        // Phase 2: enforce edge authentication on every non-public route, then rate-limit. The
        // token is still forwarded downstream below (services keep validating it themselves).
        ResponseEntity<byte[]> rejection = admit(request, path);
        if (rejection != null) {
            return rejection;
        }

        URI target = buildTargetUri(route, path, request.getQueryString());
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        // Enforce the max body size at the edge. A Content-Length that already exceeds the cap is
        // rejected without reading a byte; an absent/chunked length is guarded by the bounded read
        // below, which stops at the limit instead of buffering an unbounded body into memory.
        long maxBody = properties.getLimits().getMaxBodyBytes();
        if (request.getContentLengthLong() > maxBody) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Request body exceeds the " + maxBody + "-byte limit");
        }
        byte[] body = readBoundedBody(request, maxBody);
        if (body == null) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Request body exceeds the " + maxBody + "-byte limit");
        }

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
     * Relays a downstream event stream (SSE) to the caller <b>without buffering it</b>.
     *
     * <p>A separate handler rather than a branch inside {@link #proxy}: {@code produces} makes this
     * mapping win whenever the caller's Accept header asks for an event stream, and it keeps the
     * buffering catch-all (which reads the whole body before replying, and would therefore never
     * answer an endless stream at all) untouched.
     *
     * <p><b>Why this writes to the raw {@link HttpServletResponse} instead of returning a
     * {@code StreamingResponseBody}.</b> The Spring-idiomatic streaming return types put the write
     * on an async dispatch, and there {@code flush()} did not push bytes onto the socket — the
     * relay read and wrote every event correctly and the client still received nothing until the
     * exchange finished, which for an endless stream is never. Writing to the servlet stream on the
     * request thread and calling {@link HttpServletResponse#flushBuffer()} commits the headers
     * immediately and pushes each event as it arrives. The cost is one blocked Tomcat thread per
     * open stream, which at this scale is a fine trade for a mechanism that actually works.
     *
     * <p>{@code send()} returns as soon as the response headers arrive, so the status is known
     * before the body is pumped. The loop ends when the downstream closes or the client goes away
     * (an IOException on write — expected, not an error).
     */
    @RequestMapping(value = "/**", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void proxyStream(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String path = request.getRequestURI();

        GatewayProperties.Route route = resolve(path);
        if (route == null) {
            writeError(response, error(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "No route matches " + path));
            return;
        }
        ResponseEntity<byte[]> rejection = admit(request, path);
        if (rejection != null) {
            writeError(response, rejection);
            return;
        }

        URI target = buildTargetUri(route, path, request.getQueryString());

        java.net.http.HttpRequest.Builder builder =
                java.net.http.HttpRequest.newBuilder(target).GET();
        String authorization = request.getHeader(AUTHORIZATION);
        if (authorization != null) {
            builder.header(AUTHORIZATION, authorization);
        }
        builder.header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            builder.header(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }

        java.net.http.HttpResponse<java.io.InputStream> downstream;
        try {
            downstream = streamingClient.send(builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(response, error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Downstream service is unavailable"));
            return;
        } catch (IOException e) {
            log.warn("Stream unreachable: {} -> {}", path, target, e);
            writeError(response, error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Downstream service is unavailable"));
            return;
        }

        response.setStatus(downstream.statusCode());
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        // Tells any buffering reverse proxy in front of us not to hold the stream back.
        response.setHeader("X-Accel-Buffering", "no");
        response.flushBuffer(); // commit the headers now, before the first event exists

        try (java.io.InputStream in = downstream.body();
             java.io.OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush(); // each event goes out on its own, not when a buffer happens to fill
            }
        } catch (IOException disconnected) {
            log.debug("SSE client disconnected: {}", target);
        }
    }

    /** Writes a gateway error envelope to a raw response (the streaming handler's error path). */
    private void writeError(HttpServletResponse response, ResponseEntity<byte[]> error)
            throws IOException {
        byte[] payload = error.getBody() == null ? new byte[0] : error.getBody();
        response.setStatus(error.getStatusCode().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(payload);
        response.flushBuffer();
    }

    /**
     * Authenticates (when the route is not public) and then rate-limits, in that order so a
     * verified request can be counted against its own userId instead of a shared IP.
     *
     * @return an error response to return immediately, or {@code null} when the request may proceed
     */
    private ResponseEntity<byte[]> admit(HttpServletRequest request, String path) {
        String userId = null;
        if (!isPublic(request.getMethod(), path)) {
            JwtAuthenticator.Result result = authenticator.authenticate(request.getHeader(AUTHORIZATION));
            ResponseEntity<byte[]> authError = toAuthError(result.outcome());
            if (authError != null) {
                return authError;
            }
            userId = result.userId();
        }

        // A verified userId keys the generous per-user bucket; anything else (public route, or a
        // token without the claim) falls back to the tighter per-IP one.
        RateLimiter.Decision decision = userId != null
                ? rateLimiter.check(RateLimiter.Scope.AUTHENTICATED, userId)
                : rateLimiter.check(RateLimiter.Scope.ANONYMOUS, ClientIpResolver.resolve(request));
        if (!decision.allowed()) {
            log.warn("Rate limit exceeded: {} {}", request.getMethod(), path);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()))
                    .body(errorBody("RATE_LIMITED",
                            "Too many requests; retry in " + decision.retryAfterSeconds() + "s"));
        }
        return null;
    }

    /**
     * Maps a validation failure to the frozen auth error contract (docs/ADR-0002 §5).
     * Returns {@code null} when the request is authenticated and may proceed.
     */
    private ResponseEntity<byte[]> toAuthError(JwtAuthenticator.Outcome outcome) {
        return switch (outcome) {
            case AUTHENTICATED -> null;
            case MISSING -> error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Authentication required");
            case EXPIRED -> error(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED",
                    "Access token has expired");
            case INVALID -> error(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID",
                    "Access token is invalid");
            case REVOKED -> error(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED",
                    "Access token has been revoked");
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

    /**
     * Reads the request body while enforcing {@code maxBody}. Returns the bytes, or {@code null}
     * if the stream exceeds the cap — read incrementally and abandoned as soon as the limit is
     * passed, so an oversized (or chunked, length-unknown) body is never buffered whole.
     */
    private static byte[] readBoundedBody(HttpServletRequest request, long maxBody) throws IOException {
        try (java.io.InputStream in = request.getInputStream()) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBody) {
                    return null; // over the cap — stop; caller maps this to 413
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
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
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorBody(code, message));
    }

    /** The envelope bytes on their own, for the one error that also needs a header (429). */
    private static byte[] errorBody(String code, String message) {
        String json = "{\"success\":false,\"error\":{\"code\":\"" + escape(code)
                + "\",\"message\":\"" + escape(message) + "\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
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
