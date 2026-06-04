package com.pm.authservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for HTTP-level tests. Boots the real security filter chain on MySQL + Redis
 * containers and exposes a {@link MockMvc} plus small helpers to register/login
 * through the genuine API (no security mocked or bypassed).
 */
@AutoConfigureMockMvc
public abstract class AbstractMockMvcIntegrationTest extends AbstractIntegrationTest {

    /** Distinct per call so the shared containers never collide on unique columns. */
    private static final AtomicLong SEQUENCE = new AtomicLong(100_000L);

    @Autowired
    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${jwt.secret}")
    protected String jwtSecret;

    protected long uniqueId() {
        return SEQUENCE.incrementAndGet();
    }

    protected String registerBody(String username, String email, String password) {
        return """
                {"username":"%s","email":"%s","password":"%s"}
                """.formatted(username, email, password);
    }

    /** Registers a user via the API (expects 200) and returns its email. */
    protected String register(String username, String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(username, email, password)))
                .andExpect(status().isOk());
        return email;
    }

    /** Logs in via the API (expects 200) and returns the parsed response body. */
    protected JsonNode login(String email, String password) throws Exception {
        String body = """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    protected String refreshBody(String refreshToken) {
        return """
                {"refreshToken":"%s"}
                """.formatted(refreshToken);
    }
}
