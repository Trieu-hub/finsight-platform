package com.pm.gateway;

import com.pm.gateway.security.TokenRevocationChecker;
import com.pm.gateway.support.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
        "gateway.routes[0].uri=http://localhost:59999"
})
@AutoConfigureMockMvc
class PrometheusEndpointTest {

    /** Keeps this context off Redis; the scrape endpoint has nothing to do with revocation. */
    @MockitoBean
    private TokenRevocationChecker revocationChecker;

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JwtTestTokens::publicKeyBase64);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void prometheusEndpointIsReachableAndCarriesJvmMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory")));
    }
}
