package com.pm.dashboardservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Single {@link RestClient.Builder} used by the upstream clients. Carries explicit
 * connect/read timeouts so a slow or hung upstream surfaces as a controlled error
 * (mapped to 502) instead of blocking a request thread. Each client sets its own
 * base URI; per-request the caller's bearer token is relayed.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient.Builder upstreamRestClientBuilder(DashboardProperties properties) {
        DashboardProperties.Timeouts t = properties.getTimeouts();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(t.getConnectMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(t.getReadMs()));

        return RestClient.builder().requestFactory(factory);
    }
}
