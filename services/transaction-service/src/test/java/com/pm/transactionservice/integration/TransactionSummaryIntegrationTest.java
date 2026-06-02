package com.pm.transactionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 3: analytics summary endpoints (monthly, category breakdown, daily trend). */
class TransactionSummaryIntegrationTest extends AbstractMockMvcIntegrationTest {

    private JsonNode getJson(long userId, String path) throws Exception {
        String raw = mockMvc.perform(get(path).header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return asJson(raw);
    }

    @Test
    void monthlySummaryReturnsIncomeExpenseAndBalance() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "INCOME", "1000.00", "USD", 1L, "2026-06-10");
        createTransaction(userId, "EXPENSE", "250.00", "USD", 4L, "2026-06-11");
        createTransaction(userId, "EXPENSE", "100.00", "USD", 5L, "2026-06-12");
        // Outside June: must be excluded.
        createTransaction(userId, "EXPENSE", "999.00", "USD", 4L, "2026-05-31");

        JsonNode data = getJson(userId, "/api/v1/transactions/summary/monthly?year=2026&month=6").path("data");

        assertThat(data.path("income").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(data.path("expense").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(data.path("balance").decimalValue()).isEqualByComparingTo("650.00");
    }

    @Test
    void monthlySummaryIsZeroedWhenNoTransactions() throws Exception {
        long userId = uniqueUserId();
        JsonNode data = getJson(userId, "/api/v1/transactions/summary/monthly?year=2030&month=1").path("data");

        assertThat(data.path("income").decimalValue()).isEqualByComparingTo("0");
        assertThat(data.path("expense").decimalValue()).isEqualByComparingTo("0");
        assertThat(data.path("balance").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void categoryBreakdownIsGroupedAndOrderedByTotalDesc() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "250.00", "USD", 4L, "2026-06-11"); // Food & Dining
        createTransaction(userId, "EXPENSE", "50.00", "USD", 4L, "2026-06-12");  // Food & Dining
        createTransaction(userId, "EXPENSE", "100.00", "USD", 5L, "2026-06-13"); // Transport

        JsonNode rows = getJson(userId, "/api/v1/transactions/summary/categories").path("data");

        assertThat(rows.size()).isEqualTo(2);
        JsonNode top = rows.get(0);
        assertThat(top.path("categoryId").asLong()).isEqualTo(4);
        assertThat(top.path("categoryName").asText()).isEqualTo("Food & Dining");
        assertThat(top.path("total").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(top.path("count").asLong()).isEqualTo(2);

        JsonNode second = rows.get(1);
        assertThat(second.path("categoryId").asLong()).isEqualTo(5);
        assertThat(second.path("total").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    void dailyTrendAggregatesPerDayAscending() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "INCOME", "100.00", "USD", 1L, "2026-06-01");
        createTransaction(userId, "EXPENSE", "40.00", "USD", 4L, "2026-06-01");
        createTransaction(userId, "EXPENSE", "30.00", "USD", 4L, "2026-06-02");

        JsonNode points = getJson(userId,
                "/api/v1/transactions/summary/trend?fromDate=2026-06-01&toDate=2026-06-30").path("data");

        assertThat(points.size()).isEqualTo(2);

        JsonNode day1 = points.get(0);
        assertThat(day1.path("date").asText()).isEqualTo("2026-06-01");
        assertThat(day1.path("income").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(day1.path("expense").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(day1.path("balance").decimalValue()).isEqualByComparingTo("60.00");

        JsonNode day2 = points.get(1);
        assertThat(day2.path("date").asText()).isEqualTo("2026-06-02");
        assertThat(day2.path("income").decimalValue()).isEqualByComparingTo("0");
        assertThat(day2.path("expense").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(day2.path("balance").decimalValue()).isEqualByComparingTo("-30.00");
    }

    @Test
    void summariesAreScopedToTheAuthenticatedUser() throws Exception {
        long userA = uniqueUserId();
        long userB = uniqueUserId();
        createTransaction(userA, "INCOME", "500.00", "USD", 1L, "2026-06-10");

        // User B has no data in June; A's income must not leak.
        JsonNode data = getJson(userB, "/api/v1/transactions/summary/monthly?year=2026&month=6").path("data");
        assertThat(data.path("income").decimalValue()).isEqualByComparingTo("0");
    }
}
