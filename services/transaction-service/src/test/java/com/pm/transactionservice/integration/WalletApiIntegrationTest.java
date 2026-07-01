package com.pm.transactionservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wallet CRUD plus the balance side effects driven by transaction writes. */
class WalletApiIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void listReturnsOnlyTheUsersWallets() throws Exception {
        long userId = uniqueUserId();
        createWallet(userId, "Cash", "CASH", "USD", "0.00");
        createWallet(userId, "Bank", "BANK", "USD", "0.00");

        mockMvc.perform(get("/api/v1/wallets").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void incomeCreditsAndExpenseDebitsTheWallet() throws Exception {
        long userId = uniqueUserId();
        long wallet = createWallet(userId, "Cash", "CASH", "USD", "100.00");

        postTransaction(userId, """
                {"type":"INCOME","amount":50.00,"currency":"USD","categoryId":1,
                 "transactionDate":"2026-06-01","walletId":%d}
                """.formatted(wallet));
        assertEquals(0, walletBalance(userId, wallet).compareTo(new BigDecimal("150.00")));

        postTransaction(userId, """
                {"type":"EXPENSE","amount":30.00,"currency":"USD","categoryId":4,
                 "transactionDate":"2026-06-02","walletId":%d}
                """.formatted(wallet));
        assertEquals(0, walletBalance(userId, wallet).compareTo(new BigDecimal("120.00")));
    }

    @Test
    void rejectsTransactionWhoseCurrencyDiffersFromTheWallet() throws Exception {
        long userId = uniqueUserId();
        long wallet = createWallet(userId, "Cash", "CASH", "USD", "100.00");

        String body = """
                {"type":"EXPENSE","amount":10.00,"currency":"EUR","categoryId":4,
                 "transactionDate":"2026-06-02","walletId":%d}
                """.formatted(wallet);
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void deletingTransactionRestoresTheWalletBalance() throws Exception {
        long userId = uniqueUserId();
        long wallet = createWallet(userId, "Cash", "CASH", "USD", "100.00");

        String id = postTransaction(userId, """
                {"type":"EXPENSE","amount":40.00,"currency":"USD","categoryId":4,
                 "transactionDate":"2026-06-02","walletId":%d}
                """.formatted(wallet));
        assertEquals(0, walletBalance(userId, wallet).compareTo(new BigDecimal("60.00")));

        mockMvc.perform(delete("/api/v1/transactions/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNoContent());
        assertEquals(0, walletBalance(userId, wallet).compareTo(new BigDecimal("100.00")));
    }

    @Test
    void emptyWalletCanBeDeletedButANonEmptyOneCannot() throws Exception {
        long userId = uniqueUserId();

        long empty = createWallet(userId, "Empty", "CASH", "USD", "0.00");
        mockMvc.perform(delete("/api/v1/wallets/" + empty).header("Authorization", bearer(userId)))
                .andExpect(status().isNoContent());

        long funded = createWallet(userId, "Funded", "CASH", "USD", "100.00");
        mockMvc.perform(delete("/api/v1/wallets/" + funded).header("Authorization", bearer(userId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WALLET_NOT_EMPTY"));
    }

    /** POSTs a transaction body and returns the created id. */
    private String postTransaction(long userId, String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asText();
    }
}
