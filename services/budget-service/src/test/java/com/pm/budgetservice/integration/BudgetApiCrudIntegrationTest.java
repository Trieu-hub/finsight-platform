package com.pm.budgetservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Group B: CRUD lifecycle plus validation- and conflict-error envelopes. */
class BudgetApiCrudIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void createReturnsCreatedBudget() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"name":"Groceries","categoryId":4,"periodType":"MONTHLY",
                 "startDate":"2026-06-01","endDate":"2026-06-30",
                 "limitAmount":500.00,"currency":"USD"}
                """;

        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.userId").value((int) userId))
                .andExpect(jsonPath("$.data.name").value("Groceries"))
                .andExpect(jsonPath("$.data.periodType").value("MONTHLY"))
                .andExpect(jsonPath("$.data.categoryId").value(4))
                .andExpect(jsonPath("$.data.currency").value("USD"));
    }

    @Test
    void getByIdReturnsBudget() throws Exception {
        long userId = uniqueUserId();
        String id = createBudget(userId, 6L, "MONTHLY", "2026-05-01", "2026-05-31", "1200.00", "EUR");

        mockMvc.perform(get("/api/v1/budgets/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.currency").value("EUR"));
    }

    @Test
    void updateAppliesPartialChanges() throws Exception {
        long userId = uniqueUserId();
        String id = createBudget(userId, 4L, "MONTHLY", "2026-06-01", "2026-06-30", "500.00", "USD");

        String patch = """
                {"name":"Updated","limitAmount":650.00}
                """;
        mockMvc.perform(put("/api/v1/budgets/" + id)
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated"))
                .andExpect(jsonPath("$.data.limitAmount").value(650.00))
                .andExpect(jsonPath("$.data.periodType").value("MONTHLY")); // untouched field preserved
    }

    @Test
    void softDeleteHidesBudgetFromSubsequentReads() throws Exception {
        long userId = uniqueUserId();
        String id = createBudget(userId, 4L, "MONTHLY", "2026-06-01", "2026-06-30", "500.00", "USD");

        mockMvc.perform(delete("/api/v1/budgets/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/budgets/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUDGET_NOT_FOUND"));
    }

    @Test
    void duplicateActiveBudgetReturnsConflict() throws Exception {
        long userId = uniqueUserId();
        createBudget(userId, 4L, "MONTHLY", "2026-06-01", "2026-06-30", "500.00", "USD");

        String dup = """
                {"categoryId":4,"periodType":"MONTHLY","startDate":"2026-06-01",
                 "endDate":"2026-06-30","limitAmount":900.00,"currency":"USD"}
                """;
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(dup))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BUDGET_ALREADY_EXISTS"));
    }

    @Test
    void recreatingAfterSoftDeleteIsAllowed() throws Exception {
        long userId = uniqueUserId();
        String id = createBudget(userId, 4L, "MONTHLY", "2026-06-01", "2026-06-30", "500.00", "USD");
        mockMvc.perform(delete("/api/v1/budgets/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNoContent());

        // Same slot is free again because the previous budget is soft-deleted.
        createBudget(userId, 4L, "MONTHLY", "2026-06-01", "2026-06-30", "777.00", "USD");
    }

    @Test
    void rejectsNonPositiveLimitWithStructuredError() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"categoryId":4,"periodType":"MONTHLY","startDate":"2026-06-01",
                 "endDate":"2026-06-30","limitAmount":0,"currency":"USD"}
                """;
        mockMvc.perform(post("/api/v1/budgets")
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
                {"categoryId":4,"periodType":"MONTHLY","startDate":"2026-06-01",
                 "endDate":"2026-06-30","limitAmount":50.00,"currency":"US"}
                """;
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsMissingStartDateWithStructuredError() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"categoryId":4,"periodType":"MONTHLY","endDate":"2026-06-30",
                 "limitAmount":50.00,"currency":"USD"}
                """;
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsEndDateBeforeStartDateWithStructuredError() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"categoryId":4,"periodType":"CUSTOM","startDate":"2026-06-30",
                 "endDate":"2026-06-01","limitAmount":50.00,"currency":"USD"}
                """;
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
