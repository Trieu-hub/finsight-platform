package com.pm.riskservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for HTTP-level integration tests. Boots the full context on a real MySQL
 * container and exposes a {@link MockMvc}. risk-service has no security stack (Phase D.1),
 * so requests carry no auth.
 */
@AutoConfigureMockMvc
public abstract class AbstractMockMvcIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    // Own mapper for parsing response bodies; Boot 4 does not expose an ObjectMapper bean
    // here (Jackson is used only by the web message converters).
    protected final ObjectMapper objectMapper = new ObjectMapper();
}
