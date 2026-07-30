package com.pm.gateway.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the caller's real IP for rate-limit keying.
 *
 * <p>The gateway never sees the browser directly: in production the chain is
 * {@code client → Cloudflare → Caddy → gateway}, so {@code getRemoteAddr()} is Caddy's container
 * address and would put the entire internet in one bucket. The forwarded headers are consulted in
 * the order that matches that chain:
 *
 * <ol>
 *   <li>{@code CF-Connecting-IP} — Cloudflare's own header, the same one
 *       docker/caddy/Caddyfile keys its limiter on. Single value, always the true client.</li>
 *   <li>{@code X-Forwarded-For} — set by Caddy. Left-most entry is the original client.</li>
 *   <li>{@code getRemoteAddr()} — direct connection (local runs, tests).</li>
 * </ol>
 *
 * <p><b>These headers are only trustworthy behind our own proxy.</b> A client that can reach the
 * gateway directly can forge either one and rotate its rate-limit key at will. That holds in
 * production, where nothing but Caddy publishes a port; it does not hold if 8080 is ever exposed,
 * and the IP-keyed limit would then be bypassable (the userId-keyed one, taken from a verified
 * token, is not).
 */
public final class ClientIpResolver {

    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String cloudflare = request.getHeader(CF_CONNECTING_IP);
        if (cloudflare != null && !cloudflare.isBlank()) {
            return cloudflare.trim();
        }

        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            // "client, proxy1, proxy2" — the left-most entry is the originating client.
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }

        return request.getRemoteAddr();
    }
}
