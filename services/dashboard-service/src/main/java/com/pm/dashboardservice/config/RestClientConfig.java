package com.pm.dashboardservice.config;

import com.pm.dashboardservice.logging.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Single {@link RestClient.Builder} used by the upstream clients. Carries explicit
 * connect/read timeouts so a slow or hung upstream surfaces as a controlled error
 * (mapped to 502) instead of blocking a request thread. Each client sets its own
 * base URI; per-request the caller's bearer token is relayed.
 *
 * <p>An interceptor relays the current request's correlation id (read from the MDC,
 * populated by {@link CorrelationIdFilter} on the inbound request thread, which is the
 * same thread RestClient executes on) to every upstream call, so a single id spans the
 * dashboard and its transaction/budget/user fan-out. A correlation id already on the
 * outgoing request is left untouched.
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

        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(correlationIdRelayInterceptor());
    }

    private ClientHttpRequestInterceptor correlationIdRelayInterceptor() {
        return (request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
            if (correlationId != null
                    && !request.getHeaders().containsHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)) {
                request.getHeaders().add(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
            }
            return execution.execute(request, body);
        };
    }
}
