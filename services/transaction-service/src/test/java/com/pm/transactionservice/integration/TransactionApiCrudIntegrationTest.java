package com.pm.transactionservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Group B: CRUD lifecycle plus validation-error envelopes. */
class TransactionApiCrudIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void createReturnsCreatedTransaction() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"type":"EXPENSE","amount":42.50,"currency":"USD","categoryId":4,
                 "description":"Lunch","transactionDate":"2026-06-01","metadata":{"merchant":"Cafe"}}
                """;

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.userId").value((int) userId))
                .andExpect(jsonPath("$.data.type").value("EXPENSE"))
                .andExpect(jsonPath("$.data.currency").value("USD"))
                .andExpect(jsonPath("$.data.categoryId").value(4))
                .andExpect(jsonPath("$.data.metadata.merchant").value("Cafe"));
    }

    @Test
    void getByIdReturnsTransaction() throws Exception {
        long userId = uniqueUserId();
        String id = createTransaction(userId, "INCOME", "500.00", "EUR", 1L, "2026-05-15");

        mockMvc.perform(get("/api/v1/transactions/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.type").value("INCOME"));
    }

    @Test
    void updateAppliesPartialChanges() throws Exception {
        long userId = uniqueUserId();
        String id = createTransaction(userId, "EXPENSE", "10.00", "USD", 4L, "2026-06-01");

        String patch = """
                {"description":"Updated","amount":99.99}
                """;
        mockMvc.perform(put("/api/v1/transactions/" + id)
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("Updated"))
                .andExpect(jsonPath("$.data.type").value("EXPENSE")); // untouched field preserved
    }

    @Test
    void softDeleteHidesTransactionFromSubsequentReads() throws Exception {
        long userId = uniqueUserId();
        String id = createTransaction(userId, "EXPENSE", "10.00", "USD", 4L, "2026-06-01");

        mockMvc.perform(delete("/api/v1/transactions/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/transactions/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void rejectsNonPositiveAmountWithStructuredError() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"type":"EXPENSE","amount":0,"currency":"USD","categoryId":4,"transactionDate":"2026-06-01"}
                """;
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsInvalidCurrencyWithStructuredError() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"type":"EXPENSE","amount":5.00,"currency":"US","categoryId":4,"transactionDate":"2026-06-01"}
                """;
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsMissingTransactionDateWithStructuredError() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"type":"EXPENSE","amount":5.00,"currency":"USD","categoryId":4}
                """;
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsUnknownCategoryWithStructuredError() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"type":"EXPENSE","amount":5.00,"currency":"USD","categoryId":999999,"transactionDate":"2026-06-01"}
                """;
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NOT_FOUND"));
    }
}
