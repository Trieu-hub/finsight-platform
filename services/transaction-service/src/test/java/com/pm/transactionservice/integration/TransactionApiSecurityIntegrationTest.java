package com.pm.transactionservice.integration;

import com.pm.transactionservice.integration.support.JwtTestTokens;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Group A: authentication, authorization and per-user data isolation. */
class TransactionApiSecurityIntegrationTest extends AbstractMockMvcIntegrationTest {

    private static final String WRONG_SECRET =
            "wrong-secret-wrong-secret-wrong-secret-wrong-secret-0123456789ab";

    @Test
    void rejectsRequestWithoutJwt() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsJwtWithInvalidSignature() throws Exception {
        String forged = JwtTestTokens.valid(WRONG_SECRET, 1L, "a@b.c", "USER");
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredJwt() throws Exception {
        String expired = JwtTestTokens.expired(jwtSecret, 1L, "a@b.c", "USER");
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsValidJwtAndScopesDataToTokenUserId() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "10.00", "USD", 4L, "2026-06-01");

        // userId in the response comes from the token, not the (userId-less) request body.
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].userId").value((int) userId));
    }

    @Test
    void userCannotAccessAnotherUsersTransaction() throws Exception {
        long userA = uniqueUserId();
        long userB = uniqueUserId();
        String id = createTransaction(userA, "EXPENSE", "20.00", "USD", 4L, "2026-06-01");

        // Direct fetch by id is denied for a different owner (404, not 403 leak).
        mockMvc.perform(get("/api/v1/transactions/" + id).header("Authorization", bearer(userB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSACTION_NOT_FOUND"));

        // And it never appears in user B's listing.
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", bearer(userB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(0));

        // Owner can still see it.
        mockMvc.perform(get("/api/v1/transactions/" + id).header("Authorization", bearer(userA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id));
    }
}
