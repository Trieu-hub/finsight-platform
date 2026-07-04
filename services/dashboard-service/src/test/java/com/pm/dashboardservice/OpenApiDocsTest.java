package com.pm.dashboardservice;

import com.pm.dashboardservice.support.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the OpenAPI document is served and publicly reachable (the Swagger paths are
 * permit-listed in SecurityConfig) without weakening any API endpoint's authentication.
 * No upstreams are needed — the document is generated from the controllers alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dashboard.services.transaction-uri=http://localhost:8083",
        "dashboard.services.budget-uri=http://localhost:8084"
})
class OpenApiDocsTest {

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JwtTestTokens::publicKeyBase64);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsArePubliclyReachable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("FinSight Dashboard Service API"));
    }
}
