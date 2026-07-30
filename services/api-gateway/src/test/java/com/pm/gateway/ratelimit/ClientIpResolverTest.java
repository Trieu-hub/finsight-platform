package com.pm.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Header precedence for the rate-limit key. Getting this wrong is not a small bug: falling back to
 * the socket address behind Caddy would key every request in the world to one bucket.
 */
class ClientIpResolverTest {

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.5"); // Caddy's address on the compose network
        return request;
    }

    @Test
    void cloudflareHeaderWins() {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.1, 172.18.0.5");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void withoutCloudflare_theLeftmostForwardedEntryIsTheClient() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.1, 172.18.0.5");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.1");
    }

    @Test
    void aSingleForwardedEntryIsUsedAsIs() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-For", "198.51.100.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.1");
    }

    @Test
    void blankHeadersAreIgnored() {
        MockHttpServletRequest request = request();
        request.addHeader("CF-Connecting-IP", "  ");
        request.addHeader("X-Forwarded-For", "  ");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("172.18.0.5");
    }

    @Test
    void noForwardedHeaders_fallsBackToTheSocketAddress() {
        assertThat(ClientIpResolver.resolve(request())).isEqualTo("172.18.0.5");
    }
}
