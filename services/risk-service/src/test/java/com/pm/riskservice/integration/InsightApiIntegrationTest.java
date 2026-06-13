package com.pm.riskservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pm.riskservice.entity.BudgetSnapshot;
import com.pm.riskservice.entity.ExpenseObservation;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.repository.BudgetSnapshotRepository;
import com.pm.riskservice.repository.InsightRepository;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import com.pm.riskservice.service.InsightService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the behavioral insights (Phases E.1 + E.2) against real MySQL: seed
 * {@code observed_expenses} (and a {@code budget_snapshots} row for BUDGET_RISK), run the rules
 * via {@link InsightService}, and read results through GET /api/v1/insights. Each test isolates
 * one rule by arranging only that rule's inputs to qualify.
 */
class InsightApiIntegrationTest extends AbstractMockMvcIntegrationTest {

    private static final long USER = 9001L;
    private static final String CUR = "USD";

    @Autowired
    private ObservedExpenseRepository expenseRepository;
    @Autowired
    private InsightRepository insightRepository;
    @Autowired
    private BudgetSnapshotRepository budgetRepository;
    @Autowired
    private InsightService insightService;

    @BeforeEach
    void clean() {
        insightRepository.deleteAll();
        expenseRepository.deleteAll();
        budgetRepository.deleteAll();
    }

    @Test
    void spendingIncreaseIsGeneratedAndListed() throws Exception {
        // User-level, category-agnostic: previous month 1,000 → current 1,500 (+50%).
        seedExpense(null, "1000", "2026-05-10");
        seedExpense(null, "1000", "2026-06-05");
        seedExpense(null, "500", "2026-06-20");

        // No category on the event → only the user-level rule is evaluated.
        assertThat(insightService.evaluate(expenseOn(null, "2026-06-20"))).hasSize(1);

        JsonNode insight = onlyInsight();
        assertThat(insight.path("insightType").asText()).isEqualTo("SPENDING_INCREASE");
        assertThat(insight.path("periodMonth").asText()).isEqualTo("2026-06");
        assertThat(insight.path("categoryId").isNull()).isTrue();
        assertThat(new BigDecimal(insight.path("increasePct").asText())).isEqualByComparingTo("50.00");

        // Re-evaluating the same month does not duplicate.
        assertThat(insightService.evaluate(expenseOn(null, "2026-06-20"))).isEmpty();
        mockMvc.perform(get("/api/v1/insights")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void categorySurgeFiresWhileUserTotalDoesNot() throws Exception {
        // Category 7 surges +50% (1,000 → 1,500), but category 8 falls so the user total drops —
        // isolating CATEGORY_SURGE from SPENDING_INCREASE.
        seedExpenseCat(7L, "1000", "2026-05-10");
        seedExpenseCat(7L, "1500", "2026-06-10");
        seedExpenseCat(8L, "2000", "2026-05-11");
        seedExpenseCat(8L, "1000", "2026-06-11");

        List<?> generated = insightService.evaluate(expenseOn(7L, "2026-06-10"));
        assertThat(generated).hasSize(1);

        JsonNode insight = onlyInsight();
        assertThat(insight.path("insightType").asText()).isEqualTo("CATEGORY_SURGE");
        assertThat(insight.path("categoryId").asLong()).isEqualTo(7L);
        assertThat(new BigDecimal(insight.path("previousAmount").asText())).isEqualByComparingTo("1000");
        assertThat(new BigDecimal(insight.path("currentAmount").asText())).isEqualByComparingTo("1500");
        assertThat(new BigDecimal(insight.path("increasePct").asText())).isEqualByComparingTo("50.00");
    }

    @Test
    void budgetRiskFiresWhenUtilizationExceeds80Percent() throws Exception {
        // Only current-month data and a matching budget → spending/category have no baseline and
        // do not fire; only BUDGET_RISK does. Spend 900 of a 1,000 limit = 90% utilization.
        UUID budgetId = UUID.randomUUID();
        budgetRepository.save(new BudgetSnapshot(budgetId, USER, 7L, CUR, new BigDecimal("1000"),
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"), false, Instant.now()));
        seedExpenseCat(7L, "900", "2026-06-15");

        List<?> generated = insightService.evaluate(expenseOn(7L, "2026-06-15"));
        assertThat(generated).hasSize(1);

        JsonNode insight = onlyInsight();
        assertThat(insight.path("insightType").asText()).isEqualTo("BUDGET_RISK");
        assertThat(insight.path("categoryId").asLong()).isEqualTo(7L);
        assertThat(new BigDecimal(insight.path("previousAmount").asText())).isEqualByComparingTo("1000");
        assertThat(new BigDecimal(insight.path("currentAmount").asText())).isEqualByComparingTo("900");
        assertThat(new BigDecimal(insight.path("increasePct").asText())).isEqualByComparingTo("90.00");

        // Fires once per budget — a later in-window expense does not re-alert.
        seedExpenseCat(7L, "50", "2026-06-16");
        assertThat(insightService.evaluate(expenseOn(7L, "2026-06-16"))).isEmpty();
    }

    @Test
    void listIsEmptyWhenNoInsights() throws Exception {
        mockMvc.perform(get("/api/v1/insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void generatedMetricIsExposedPerType() throws Exception {
        seedExpense(null, "1000", "2026-05-10");
        seedExpense(null, "2000", "2026-06-10");
        insightService.evaluate(expenseOn(null, "2026-06-10"));

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(metrics).contains("finsight_insights_generated_total");
        assertThat(metrics).contains("type=\"SPENDING_INCREASE\"");
        // All three series are registered eagerly (exported even at 0).
        assertThat(metrics).contains("type=\"CATEGORY_SURGE\"");
        assertThat(metrics).contains("type=\"BUDGET_RISK\"");
    }

    private JsonNode onlyInsight() throws Exception {
        String body = mockMvc.perform(get("/api/v1/insights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get(0);
    }

    private void seedExpense(Long categoryId, String amount, String date) {
        LocalDate day = LocalDate.parse(date);
        expenseRepository.save(new ExpenseObservation(
                UUID.randomUUID(), USER, categoryId, new BigDecimal(amount), CUR,
                day.atStartOfDay().toInstant(ZoneOffset.UTC), day));
    }

    private void seedExpenseCat(Long categoryId, String amount, String date) {
        seedExpense(categoryId, amount, date);
    }

    private TransactionCreatedEvent expenseOn(Long categoryId, String date) {
        return new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", date + "T10:00:00Z",
                UUID.randomUUID(), USER, "EXPENSE", new BigDecimal("100"),
                CUR, categoryId, date, 1L);
    }
}
