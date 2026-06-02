package com.pm.transactionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Group C: filtering, pagination stability and default ordering. */
class TransactionApiFilterIntegrationTest extends AbstractMockMvcIntegrationTest {

    private String listRaw(long userId, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/transactions" + query).header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void defaultSortingIsTransactionDateDesc() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "1.00", "USD", 4L, "2026-01-01");
        createTransaction(userId, "EXPENSE", "2.00", "USD", 4L, "2026-03-01");
        createTransaction(userId, "EXPENSE", "3.00", "USD", 4L, "2026-02-01");

        mockMvc.perform(get("/api/v1/transactions").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.data[0].transactionDate").value("2026-03-01"))
                .andExpect(jsonPath("$.data[1].transactionDate").value("2026-02-01"))
                .andExpect(jsonPath("$.data[2].transactionDate").value("2026-01-01"));
    }

    @Test
    void filterByTypeReturnsOnlyMatchingType() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "1.00", "USD", 4L, "2026-01-01");
        createTransaction(userId, "EXPENSE", "2.00", "USD", 4L, "2026-01-02");
        createTransaction(userId, "INCOME", "9.00", "USD", 1L, "2026-01-03");

        mockMvc.perform(get("/api/v1/transactions?type=INCOME").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].type").value("INCOME"));
    }

    @Test
    void filterByDateRangeIsInclusive() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "1.00", "USD", 4L, "2026-01-31");
        createTransaction(userId, "EXPENSE", "2.00", "USD", 4L, "2026-02-15"); // in range
        createTransaction(userId, "EXPENSE", "3.00", "USD", 4L, "2026-02-28"); // boundary, in range
        createTransaction(userId, "EXPENSE", "4.00", "USD", 4L, "2026-03-01");

        mockMvc.perform(get("/api/v1/transactions?fromDate=2026-02-01&toDate=2026-02-28")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[0].transactionDate").value("2026-02-28"))
                .andExpect(jsonPath("$.data[1].transactionDate").value("2026-02-15"));
    }

    @Test
    void combinedFiltersAreAnded() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "1.00", "USD", 4L, "2026-02-10"); // match
        createTransaction(userId, "INCOME", "2.00", "USD", 1L, "2026-02-11");  // wrong type
        createTransaction(userId, "EXPENSE", "3.00", "USD", 5L, "2026-02-12"); // wrong category
        createTransaction(userId, "EXPENSE", "4.00", "USD", 4L, "2026-04-01"); // out of range

        mockMvc.perform(get("/api/v1/transactions?type=EXPENSE&categoryId=4&fromDate=2026-02-01&toDate=2026-02-28")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].transactionDate").value("2026-02-10"))
                .andExpect(jsonPath("$.data[0].categoryId").value(4));
    }

    @Test
    void paginationIsDeterministicAcrossPagesEvenWithTiedDates() throws Exception {
        long userId = uniqueUserId();
        // Five transactions sharing the SAME date — the worst case for stable ordering.
        for (int i = 0; i < 5; i++) {
            createTransaction(userId, "EXPENSE", "1.00", "USD", 4L, "2026-07-01");
        }

        Set<String> seen = new HashSet<>();
        int total = -1;
        for (int page = 1; page <= 3; page++) {
            JsonNode body = asJson(listRaw(userId, "?page=" + page + "&limit=2"));
            total = body.path("meta").path("total").asInt();
            for (JsonNode node : body.path("data")) {
                // No id may appear on more than one page.
                assertThat(seen.add(node.path("id").asText())).isTrue();
            }
        }

        assertThat(total).isEqualTo(5);
        assertThat(seen).hasSize(5); // every row seen exactly once across the pages
    }

    @Test
    void limitAboveMaximumReturnsStructuredValidationError() throws Exception {
        long userId = uniqueUserId();
        mockMvc.perform(get("/api/v1/transactions?limit=999").header("Authorization", bearer(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void pageBelowOneReturnsStructuredValidationError() throws Exception {
        long userId = uniqueUserId();
        mockMvc.perform(get("/api/v1/transactions?page=0").header("Authorization", bearer(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
