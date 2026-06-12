package com.pm.dashboardservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Prometheus scrape endpoint is exposed, publicly reachable (permit-listed
 * in SecurityConfig) and actually carries JVM metrics — i.e. the Boot 4 metrics
 * autoconfiguration really activated. This guards against the silent Boot 4
 * modularization failure mode: a missing module produces no error, just no endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789abcdef",
        "dashboard.services.user-uri=http://localhost:8082",
        "dashboard.services.transaction-uri=http://localhost:8083",
        "dashboard.services.budget-uri=http://localhost:8084"
})
class PrometheusEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointIsPubliclyReachableAndCarriesJvmMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory")));
    }
}
