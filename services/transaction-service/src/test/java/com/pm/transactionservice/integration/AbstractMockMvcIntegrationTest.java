package com.pm.transactionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pm.transactionservice.integration.support.JwtTestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for HTTP-level integration tests. Boots the full context (security
 * filter chain included) on a real MySQL container and exposes a {@link MockMvc}
 * that exercises the genuine JWT filter — no security is mocked or bypassed.
 */
@AutoConfigureMockMvc
public abstract class AbstractMockMvcIntegrationTest extends AbstractMySqlIntegrationTest {

    /** Distinct per test so a shared container never leaks data between tests. */
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(100_000L);

    @Autowired
    protected MockMvc mockMvc;

    // Own mapper for parsing response bodies; Boot 4 does not expose an ObjectMapper
    // bean here (Jackson is used only by the web message converters), and parsing
    // JSON into a tree needs no app configuration.
    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${jwt.secret}")
    protected String jwtSecret;

    protected long uniqueUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    protected String bearer(long userId) {
        return "Bearer " + JwtTestTokens.valid(
                jwtSecret, userId, "user" + userId + "@finsight.test", "USER");
    }

    /** Creates a transaction for the user via the API and returns its generated id. */
    protected String createTransaction(long userId, String type, String amount,
                                        String currency, long categoryId, String date) throws Exception {
        String body = """
                {"type":"%s","amount":%s,"currency":"%s","categoryId":%d,"transactionDate":"%s"}
                """.formatted(type, amount, currency, categoryId, date);

        String response = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).path("data").path("id").asText();
    }

    protected JsonNode asJson(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }
}
