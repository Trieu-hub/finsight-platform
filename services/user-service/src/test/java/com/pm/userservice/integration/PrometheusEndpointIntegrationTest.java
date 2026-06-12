package com.pm.userservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Prometheus scrape endpoint is exposed, publicly reachable (permit-listed
 * in SecurityConfig) and actually carries JVM metrics — i.e. the Boot 4 metrics
 * autoconfiguration really activated. This guards against the silent Boot 4
 * modularization failure mode: a missing module produces no error, just no endpoint.
 */
@AutoConfigureMockMvc
class PrometheusEndpointIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointIsPubliclyReachableAndCarriesJvmMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory")));
    }
}
