package com.pm.budgetservice.integration;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the OpenAPI document is served and publicly reachable (the Swagger paths are
 * permit-listed in SecurityConfig) without weakening any API endpoint's authentication.
 */
class OpenApiDocsIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void apiDocsArePubliclyReachable() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("FinSight Budget Service API"));
    }
}
