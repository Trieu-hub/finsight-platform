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
