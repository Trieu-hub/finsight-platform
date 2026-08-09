package com.pm.transactionservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Group I: CSV export — what the file contains, and that it obeys the same scoping as the list. */
class TransactionExportIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Test
    void exportsTheUsersTransactionsAsADownloadableCsv() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "42.50", "USD", 4, "2026-06-01");
        createTransaction(userId, "INCOME", "1500.00", "USD", 1, "2026-06-02");

        String csv = mockMvc.perform(get("/api/v1/transactions/export")
                        .header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment; filename=")))
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).isEqualTo("date,amount,currency,type,category,description,id");
        // Newest first, matching the list endpoint's ordering. Amounts come back from a
        // DECIMAL(19,4) column with their trailing zeros stripped.
        assertThat(lines[1]).startsWith("2026-06-02,1500,USD,INCOME,");
        assertThat(lines[2]).startsWith("2026-06-01,-42.5,USD,EXPENSE,");
    }

    @Test
    void appliesTheSameFiltersAsTheListEndpoint() throws Exception {
        long userId = uniqueUserId();
        createTransaction(userId, "EXPENSE", "10.00", "USD", 4, "2026-05-20");
        createTransaction(userId, "EXPENSE", "20.00", "USD", 4, "2026-06-20");

        String csv = mockMvc.perform(get("/api/v1/transactions/export")
                        .param("fromDate", "2026-06-01")
                        .param("type", "EXPENSE")
                        .header("Authorization", bearer(userId)))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("-20,").doesNotContain("-10,");
    }

    @Test
    void neverExportsAnotherUsersTransactions() throws Exception {
        long owner = uniqueUserId();
        long stranger = uniqueUserId();
        createTransaction(owner, "EXPENSE", "77.00", "USD", 4, "2026-06-01");

        String csv = mockMvc.perform(get("/api/v1/transactions/export")
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(csv.split("\r\n")).hasSize(1);
    }

    /** A soft-deleted transaction is gone from the list, so it must be gone from the file too. */
    @Test
    void excludesADeletedTransaction() throws Exception {
        long userId = uniqueUserId();
        String id = createTransaction(userId, "EXPENSE", "33.00", "USD", 4, "2026-06-01");
        mockMvc.perform(delete("/api/v1/transactions/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNoContent());

        String csv = mockMvc.perform(get("/api/v1/transactions/export")
                        .header("Authorization", bearer(userId)))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).doesNotContain("-33,");
    }

    @Test
    void exportRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/export"))
                .andExpect(status().isUnauthorized());
    }
}
