package com.pm.budgetservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Group C: filtering, pagination stability and default ordering. */
class BudgetApiFilterIntegrationTest extends AbstractMockMvcIntegrationTest {

    private String listRaw(long userId, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/budgets" + query).header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void defaultSortingIsStartDateDesc() throws Exception {
        long userId = uniqueUserId();
        createBudget(userId, 4L, "MONTHLY", "2026-01-01", "2026-01-31", "100.00", "USD");
        createBudget(userId, 4L, "MONTHLY", "2026-03-01", "2026-03-31", "200.00", "USD");
        createBudget(userId, 4L, "MONTHLY", "2026-02-01", "2026-02-28", "300.00", "USD");

        mockMvc.perform(get("/api/v1/budgets").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.data[0].startDate").value("2026-03-01"))
                .andExpect(jsonPath("$.data[1].startDate").value("2026-02-01"))
                .andExpect(jsonPath("$.data[2].startDate").value("2026-01-01"));
    }

    @Test
    void filterByCategoryReturnsOnlyMatching() throws Exception {
        long userId = uniqueUserId();
        createBudget(userId, 4L, "MONTHLY", "2026-01-01", "2026-01-31", "100.00", "USD");
        createBudget(userId, 5L, "MONTHLY", "2026-01-01", "2026-01-31", "200.00", "USD");

        mockMvc.perform(get("/api/v1/budgets?categoryId=5").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].categoryId").value(5));
    }

    @Test
    void filterByPeriodTypeReturnsOnlyMatching() throws Exception {
        long userId = uniqueUserId();
        createBudget(userId, 4L, "MONTHLY", "2026-01-01", "2026-01-31", "100.00", "USD");
        createBudget(userId, 4L, "YEARLY", "2026-01-01", "2026-12-31", "9000.00", "USD");

        mockMvc.perform(get("/api/v1/budgets?periodType=YEARLY").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].periodType").value("YEARLY"));
    }

    @Test
    void filterByActiveOnReturnsBudgetsCoveringTheDate() throws Exception {
        long userId = uniqueUserId();
        createBudget(userId, 4L, "MONTHLY", "2026-02-01", "2026-02-28", "100.00", "USD"); // covers 2026-02-15
        createBudget(userId, 5L, "MONTHLY", "2026-03-01", "2026-03-31", "200.00", "USD"); // does not

        mockMvc.perform(get("/api/v1/budgets?activeOn=2026-02-15").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].categoryId").value(4));
    }

    @Test
    void combinedFiltersAreAnded() throws Exception {
        long userId = uniqueUserId();
        createBudget(userId, 4L, "MONTHLY", "2026-02-01", "2026-02-28", "100.00", "USD"); // match
        createBudget(userId, 5L, "MONTHLY", "2026-02-01", "2026-02-28", "200.00", "USD"); // wrong category
        createBudget(userId, 4L, "YEARLY", "2026-01-01", "2026-12-31", "300.00", "USD");  // wrong period

        mockMvc.perform(get("/api/v1/budgets?categoryId=4&periodType=MONTHLY&activeOn=2026-02-10")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].categoryId").value(4))
                .andExpect(jsonPath("$.data[0].periodType").value("MONTHLY"));
    }

    @Test
    void paginationIsDeterministicAcrossPagesEvenWithTiedDates() throws Exception {
        long userId = uniqueUserId();
        // Five budgets sharing the SAME start date but distinct categories (so no duplicate
        // conflict) — the worst case for stable ordering.
        for (int i = 0; i < 5; i++) {
            createBudget(userId, 100L + i, "CUSTOM", "2026-07-01", "2026-07-31", "10.00", "USD");
        }

        Set<String> seen = new HashSet<>();
        int total = -1;
        for (int page = 1; page <= 3; page++) {
            JsonNode body = asJson(listRaw(userId, "?page=" + page + "&limit=2"));
            total = body.path("meta").path("total").asInt();
            for (JsonNode node : body.path("data")) {
                assertThat(seen.add(node.path("id").asText())).isTrue();
            }
        }

        assertThat(total).isEqualTo(5);
        assertThat(seen).hasSize(5);
    }

    @Test
    void limitAboveMaximumReturnsStructuredValidationError() throws Exception {
        long userId = uniqueUserId();
        mockMvc.perform(get("/api/v1/budgets?limit=999").header("Authorization", bearer(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pageBelowOneReturnsStructuredValidationError() throws Exception {
        long userId = uniqueUserId();
        mockMvc.perform(get("/api/v1/budgets?page=0").header("Authorization", bearer(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
