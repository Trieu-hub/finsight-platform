package com.pm.transactionservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Group H: statement import — what lands, what is recognised as already there, what is refused. */
class TransactionImportIntegrationTest extends AbstractMockMvcIntegrationTest {

    private static final String TWO_ROWS = """
            {"transactions":[
              {"type":"EXPENSE","amount":42.50,"currency":"USD","categoryId":4,
               "description":"Lunch","transactionDate":"2026-06-01"},
              {"type":"INCOME","amount":1500.00,"currency":"USD","categoryId":1,
               "description":"Salary","transactionDate":"2026-06-02"}
            ]}
            """;

    @Test
    void importWritesEveryRow() throws Exception {
        long userId = uniqueUserId();

        importRows(userId, TWO_ROWS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.imported").value(2))
                .andExpect(jsonPath("$.data.duplicates").value(0))
                .andExpect(jsonPath("$.data.errors").isEmpty());

        mockMvc.perform(get("/api/v1/transactions").header("Authorization", bearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2));
    }

    /** The point of the fingerprint: the same statement uploaded twice must not double the rows. */
    @Test
    void reimportingTheSameStatementSkipsEveryRow() throws Exception {
        long userId = uniqueUserId();
        importRows(userId, TWO_ROWS).andExpect(status().isOk());

        importRows(userId, TWO_ROWS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(0))
                .andExpect(jsonPath("$.data.duplicates").value(2));

        mockMvc.perform(get("/api/v1/transactions").header("Authorization", bearer(userId)))
                .andExpect(jsonPath("$.meta.total").value(2));
    }

    /** A file that lists one line twice is caught before it reaches the unique index. */
    @Test
    void repeatedRowWithinOneFileIsImportedOnce() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"transactions":[
                  {"type":"EXPENSE","amount":9.99,"currency":"USD","categoryId":4,
                   "description":"Coffee","transactionDate":"2026-06-03"},
                  {"type":"EXPENSE","amount":9.99,"currency":"USD","categoryId":4,
                   "description":"Coffee","transactionDate":"2026-06-03"}
                ]}
                """;

        importRows(userId, body)
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.duplicates").value(1));
    }

    /** Trailing zeros and surrounding spaces are the same statement line, not a new one. */
    @Test
    void amountAndDescriptionAreNormalisedBeforeComparing() throws Exception {
        long userId = uniqueUserId();
        importRows(userId, """
                {"transactions":[{"type":"EXPENSE","amount":15.00,"currency":"USD","categoryId":4,
                 "description":"Taxi","transactionDate":"2026-06-04"}]}
                """).andExpect(jsonPath("$.data.imported").value(1));

        importRows(userId, """
                {"transactions":[{"type":"EXPENSE","amount":15,"currency":"USD","categoryId":4,
                 "description":"  Taxi  ","transactionDate":"2026-06-04"}]}
                """).andExpect(jsonPath("$.data.duplicates").value(1));
    }

    /** One bad row is reported by position; the rows around it are still written. */
    @Test
    void abadRowIsReportedWithoutStoppingTheRest() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"transactions":[
                  {"type":"EXPENSE","amount":5.00,"currency":"USD","categoryId":4,
                   "description":"Before","transactionDate":"2026-06-05"},
                  {"type":"EXPENSE","amount":5.00,"currency":"USD","categoryId":99999,
                   "description":"Unknown category","transactionDate":"2026-06-05"},
                  {"type":"EXPENSE","amount":6.00,"currency":"USD","categoryId":4,
                   "description":"After","transactionDate":"2026-06-05"}
                ]}
                """;

        importRows(userId, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(2))
                .andExpect(jsonPath("$.data.errors[0].row").value(2))
                .andExpect(jsonPath("$.data.errors[0].message").exists());

        mockMvc.perform(get("/api/v1/transactions").header("Authorization", bearer(userId)))
                .andExpect(jsonPath("$.meta.total").value(2));
    }

    /** Deleting an imported row releases its statement line, so the file can be imported again. */
    @Test
    void deletingAnImportedRowAllowsItToBeImportedAgain() throws Exception {
        long userId = uniqueUserId();
        String body = """
                {"transactions":[{"type":"EXPENSE","amount":20.00,"currency":"USD","categoryId":4,
                 "description":"Refunded","transactionDate":"2026-06-06"}]}
                """;
        importRows(userId, body).andExpect(jsonPath("$.data.imported").value(1));

        String listed = mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", bearer(userId)))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(listed).path("data").get(0).path("id").asText();

        mockMvc.perform(delete("/api/v1/transactions/" + id).header("Authorization", bearer(userId)))
                .andExpect(status().isNoContent());

        importRows(userId, body).andExpect(jsonPath("$.data.imported").value(1));
    }

    /** One user's import must never collide with another's identical statement line. */
    @Test
    void anIdenticalRowFromAnotherUserIsNotADuplicate() throws Exception {
        long first = uniqueUserId();
        long second = uniqueUserId();
        String body = """
                {"transactions":[{"type":"EXPENSE","amount":30.00,"currency":"USD","categoryId":4,
                 "description":"Shared merchant","transactionDate":"2026-06-07"}]}
                """;

        importRows(first, body).andExpect(jsonPath("$.data.imported").value(1));
        importRows(second, body).andExpect(jsonPath("$.data.imported").value(1));
    }

    @Test
    void anEmptyImportIsRejected() throws Exception {
        importRows(uniqueUserId(), "{\"transactions\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void importRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_ROWS))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions importRows(long userId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/import")
                .header("Authorization", bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
