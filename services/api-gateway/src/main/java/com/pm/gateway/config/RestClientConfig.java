package com.pm.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Builds the single {@link RestClient} the proxy uses to forward requests.
 *
 * <p>Uses the JDK {@link HttpClient} factory because it supports all HTTP methods
 * (including PATCH, which {@code SimpleClientHttpRequestFactory} does not) and lets
 * us set explicit connect/read timeouts — a downstream that hangs must surface as a
 * {@code 504 SERVICE_TIMEOUT}, never block a gateway thread indefinitely.
 */
@Configuration
public class RestClientConfig {

    /**
     * A second client for streaming relays (SSE). Deliberately has <b>no read timeout</b>: an
     * event stream is idle by nature between events, and the {@code readMs} that protects the
     * request/response path would tear a healthy stream down every 10 seconds. Liveness is the
     * stream's own problem — notification-service sends a heartbeat comment.
     */
    @Bean
    HttpClient streamingClient(GatewayProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeouts().getConnectMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                // Pinned to HTTP/1.1. The JDK client otherwise attempts an h2c upgrade on
                // cleartext, which buys nothing for a one-way event stream and complicates when
                // bytes are actually handed to the reader. SSE wants the simplest possible
                // chunked-transfer path.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean
    RestClient downstreamClient(GatewayProperties properties) {
        GatewayProperties.Timeouts t = properties.getTimeouts();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(t.getConnectMs()))
                // Do not auto-follow redirects: a downstream redirect must be relayed
                // to the caller verbatim, not silently resolved by the gateway.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(t.getReadMs()));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
