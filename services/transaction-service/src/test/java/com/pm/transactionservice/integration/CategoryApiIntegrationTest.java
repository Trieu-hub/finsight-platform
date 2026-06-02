package com.pm.transactionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 3: category management CRUD and protection rules. */
class CategoryApiIntegrationTest extends AbstractMockMvcIntegrationTest {

    /** Creates a category via the API and returns its generated id. */
    private long createCategory(long actor, String name, String type, String icon, String color) throws Exception {
        String body = """
                {"name":"%s","type":"%s","icon":"%s","color":"%s"}
                """.formatted(name, type, icon, color);
        String raw = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return asJson(raw).path("data").path("id").asLong();
    }

    @Test
    void listIncludesSeededSystemCategories() throws Exception {
        long actor = uniqueUserId();
        String raw = mockMvc.perform(get("/api/v1/categories").header("Authorization", bearer(actor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = asJson(raw).path("data");
        assertThat(rows.size()).isGreaterThanOrEqualTo(10);

        JsonNode salary = null;
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == 1L) {
                salary = row;
                break;
            }
        }
        assertThat(salary).isNotNull();
        assertThat(salary.path("name").asText()).isEqualTo("Salary");
        assertThat(salary.path("isSystem").asBoolean()).isTrue();
    }

    @Test
    void createReturnsNonSystemCategoryWithGeneratedId() throws Exception {
        long actor = uniqueUserId();
        String raw = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Travel","type":"EXPENSE","icon":"plane","color":"#FF0000"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = asJson(raw).path("data");
        assertThat(data.path("id").asLong()).isGreaterThan(10L);
        assertThat(data.path("name").asText()).isEqualTo("Travel");
        assertThat(data.path("type").asText()).isEqualTo("EXPENSE");
        assertThat(data.path("icon").asText()).isEqualTo("plane");
        assertThat(data.path("color").asText()).isEqualTo("#FF0000");
        assertThat(data.path("isSystem").asBoolean()).isFalse();
    }

    @Test
    void updateAppliesPartialChangesAndKeepsTypeWhenAbsent() throws Exception {
        long actor = uniqueUserId();
        long id = createCategory(actor, "Groceries", "EXPENSE", "cart", "#111111");

        mockMvc.perform(put("/api/v1/categories/" + id)
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Groceries & Home","color":"#222222"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Groceries & Home"))
                .andExpect(jsonPath("$.data.color").value("#222222"))
                .andExpect(jsonPath("$.data.type").value("EXPENSE"))
                .andExpect(jsonPath("$.data.isSystem").value(false));
    }

    @Test
    void deleteUserCreatedCategorySucceeds() throws Exception {
        long actor = uniqueUserId();
        long id = createCategory(actor, "Temp", "EXPENSE", null, null);

        mockMvc.perform(delete("/api/v1/categories/" + id).header("Authorization", bearer(actor)))
                .andExpect(status().isNoContent());

        // Gone now → addressing it again is a 404.
        mockMvc.perform(delete("/api/v1/categories/" + id).header("Authorization", bearer(actor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void cannotDeleteSystemCategory() throws Exception {
        long actor = uniqueUserId();
        mockMvc.perform(delete("/api/v1/categories/1").header("Authorization", bearer(actor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_PROTECTED"));
    }

    @Test
    void cannotDeleteCategoryReferencedByTransactions() throws Exception {
        long actor = uniqueUserId();
        long id = createCategory(actor, "Subscriptions", "EXPENSE", null, null);
        createTransaction(actor, "EXPENSE", "9.99", "USD", id, "2026-06-01");

        mockMvc.perform(delete("/api/v1/categories/" + id).header("Authorization", bearer(actor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_IN_USE"));
    }

    @Test
    void updateMissingCategoryReturns404() throws Exception {
        long actor = uniqueUserId();
        mockMvc.perform(put("/api/v1/categories/999999")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nope"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void createWithBlankNameReturnsValidationError() throws Exception {
        long actor = uniqueUserId();
        mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","type":"EXPENSE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
