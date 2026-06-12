package com.pm.authservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Prometheus scrape endpoint is exposed, publicly reachable (permit-listed
 * in SecurityConfig) and actually carries JVM metrics — i.e. the Boot 4 metrics
 * autoconfiguration really activated. This guards against the silent Boot 4
 * modularization failure mode: a missing module produces no error, just no endpoint.
 *
 * <p>{@code @AutoConfigureMetrics} is required here and only here: auth-service is the
 * one module with {@code spring-boot-starter-actuator-test} on its test classpath,
 * whose {@code DisableMetricsExportContextCustomizer} turns metrics export off in
 * every {@code @SpringBootTest} by default.
 */
@AutoConfigureMetrics
class PrometheusEndpointIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void prometheusEndpointIsPubliclyReachableAndCarriesJvmMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory")));
    }
}
