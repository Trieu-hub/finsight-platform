package com.pm.transactionservice.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Replaying a create must resolve to the row the first attempt made, not to a second one.
 *
 * <p>This is what lets the SPA queue a transaction composed offline and send it later without the
 * user ending up with two of everything: the queue cannot know whether a request that timed out
 * was actually applied, so it has to be safe to send again. The guarantee is a unique index on
 * {@code (user_id, client_request_id)} — the service's own lookup only makes the common case
 * cheap, and a test against real MySQL is the only place the index itself is exercised.
 */
class TransactionIdempotencyIntegrationTest extends AbstractMockMvcIntegrationTest {

    private String body(String clientRequestId, String amount) {
        return """
                {"type":"EXPENSE","amount":%s,"currency":"USD","categoryId":4,
                 "description":"Queued offline","transactionDate":"2026-06-01",
                 "clientRequestId":"%s"}
                """.formatted(amount, clientRequestId);
    }

    private String create(long userId, String requestBody, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a replayed write returns the first transaction instead of making another")
    void replayIsIdempotent() throws Exception {
        long userId = uniqueUserId();
        String token = "offline-" + userId;

        String first = create(userId, body(token, "42.50"), 201);
        String second = create(userId, body(token, "42.50"), 201);

        String firstId = com.jayway.jsonpath.JsonPath.read(first, "$.data.id");
        String secondId = com.jayway.jsonpath.JsonPath.read(second, "$.data.id");
        org.assertj.core.api.Assertions.assertThat(secondId).isEqualTo(firstId);

        // And the ledger holds one, not two — the assertion that actually matters to a user.
        mockMvc.perform(get("/api/v1/transactions?from=2026-06-01&to=2026-06-30")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("the token is scoped to its user, so two people cannot collide")
    void tokensAreScopedPerUser() throws Exception {
        long alice = uniqueUserId();
        long bob = uniqueUserId();
        // Clients generate these independently; nothing stops two of them picking the same string,
        // and one user's write must never silently resolve to another user's transaction.
        String token = "same-token-both-users";

        create(alice, body(token, "10.00"), 201);
        create(bob, body(token, "20.00"), 201);

        mockMvc.perform(get("/api/v1/transactions?from=2026-06-01&to=2026-06-30")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(20.00));
    }

    @Test
    @DisplayName("without a token every create is a new transaction, as before")
    void staysUnchangedWithoutAToken() throws Exception {
        long userId = uniqueUserId();
        String noToken = """
                {"type":"EXPENSE","amount":5.00,"currency":"USD","categoryId":4,
                 "transactionDate":"2026-06-02"}
                """;

        create(userId, noToken, 201);
        create(userId, noToken, 201);

        // Two identical hand-entered coffees are two coffees. Deduping them would be the app
        // deciding it knows better than the person who typed them.
        mockMvc.perform(get("/api/v1/transactions?from=2026-06-01&to=2026-06-30")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("an empty token is treated as no token, not as one everybody shares")
    void blankTokenDoesNotClaimTheIndex() throws Exception {
        long userId = uniqueUserId();
        String blank = """
                {"type":"EXPENSE","amount":7.00,"currency":"USD","categoryId":4,
                 "transactionDate":"2026-06-03","clientRequestId":"  "}
                """;

        create(userId, blank, 201);
        // Stored as "" or "  " this would occupy the unique slot and make the user's *next* blank
        // create resolve to this one.
        create(userId, blank, 201);

        mockMvc.perform(get("/api/v1/transactions?from=2026-06-01&to=2026-06-30")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }
}
