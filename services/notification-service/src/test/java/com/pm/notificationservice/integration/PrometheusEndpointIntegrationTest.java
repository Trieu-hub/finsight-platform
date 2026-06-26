package com.pm.notificationservice.integration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Prometheus scrape endpoint is exposed, publicly reachable (permit-listed
 * in SecurityConfig) and actually carries JVM metrics — i.e. the Boot 4 metrics
 * autoconfiguration really activated. This guards against the silent Boot 4
 * modularization failure mode: a missing module produces no error, just no endpoint.
 * The consumer-outcome counters (finsight.budget.events.*) become scrapeable through
 * this same endpoint once incremented.
 */
class PrometheusEndpointIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void prometheusEndpointIsPubliclyReachableAndCarriesJvmMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory")));
    }
}
