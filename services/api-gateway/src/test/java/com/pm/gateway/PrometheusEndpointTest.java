package com.pm.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Prometheus scrape endpoint is exposed, carries JVM metrics (the Boot 4
 * metrics autoconfiguration really activated) and is served by the actuator handler
 * mapping rather than swallowed by the gateway's catch-all proxy controller.
 */
@SpringBootTest(properties = {
        "gateway.routes[0].prefix=/api/v1/auth",
        "gateway.routes[0].uri=http://localhost:59999",
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class PrometheusEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointIsReachableAndCarriesJvmMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory")));
    }
}
