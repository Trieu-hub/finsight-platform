package com.pm.riskservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pm.riskservice.entity.ExpenseObservation;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.repository.AnomalyRepository;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import com.pm.riskservice.service.AnomalyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the UNUSUAL_TRANSACTION_AMOUNT anomaly (Phase F.1) against real MySQL:
 * seed a user's prior {@code observed_expenses}, run detection via {@link AnomalyService}, and
 * read results through GET /api/v1/anomalies.
 */
class AnomalyApiIntegrationTest extends AbstractMockMvcIntegrationTest {

    private static final long USER = 9101L;
    private static final String CUR = "USD";

    @Autowired
    private ObservedExpenseRepository expenseRepository;
    @Autowired
    private AnomalyRepository anomalyRepository;
    @Autowired
    private AnomalyService anomalyService;

    @BeforeEach
    void clean() {
        anomalyRepository.deleteAll();
        expenseRepository.deleteAll();
    }

    @Test
    void unusualAmountIsDetectedAndListed() throws Exception {
        // Ten prior expenses of 100 (average 100); the eleventh of 500 is 5× the average.
        seedTenPriorExpensesOf("100");

        UUID txId = UUID.randomUUID();
        // Same event instance (and event id) for both calls — modelling at-least-once redelivery.
        TransactionCreatedEvent event = expense(txId, "500", "2026-06-20T10:00:00Z");
        assertThat(anomalyService.evaluate(event)).isPresent();

        JsonNode anomaly = onlyAnomaly();
        assertThat(anomaly.path("anomalyType").asText()).isEqualTo("UNUSUAL_TRANSACTION_AMOUNT");
        assertThat(anomaly.path("userId").asLong()).isEqualTo(USER);
        assertThat(anomaly.path("transactionId").asText()).isEqualTo(txId.toString());
        assertThat(new BigDecimal(anomaly.path("amount").asText())).isEqualByComparingTo("500");
        assertThat(new BigDecimal(anomaly.path("averageAmount").asText())).isEqualByComparingTo("100");
        assertThat(new BigDecimal(anomaly.path("ratio").asText())).isEqualByComparingTo("5.00");

        // Idempotent: re-evaluating the same event does not duplicate.
        assertThat(anomalyService.evaluate(event)).isEmpty();
        mockMvc.perform(get("/api/v1/anomalies")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void doesNotDetectWhenHistoryIsInsufficient() throws Exception {
        // Only nine prior expenses — one short of the 10 minimum — so no anomaly even though
        // 500 is far above the average.
        for (int i = 1; i <= 9; i++) {
            seedExpense("100", "2026-06-" + String.format("%02d", i) + "T08:00:00Z");
        }

        assertThat(anomalyService.evaluate(expense(UUID.randomUUID(), "500", "2026-06-20T10:00:00Z")))
                .isEmpty();
        mockMvc.perform(get("/api/v1/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listIsEmptyWhenNoAnomalies() throws Exception {
        mockMvc.perform(get("/api/v1/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void detectedMetricIsExposed() throws Exception {
        seedTenPriorExpensesOf("100");
        anomalyService.evaluate(expense(UUID.randomUUID(), "500", "2026-06-20T10:00:00Z"));

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(metrics).contains("finsight_anomalies_detected_total");
        assertThat(metrics).contains("type=\"UNUSUAL_TRANSACTION_AMOUNT\"");
    }

    private JsonNode onlyAnomaly() throws Exception {
        String body = mockMvc.perform(get("/api/v1/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get(0);
    }

    private void seedTenPriorExpensesOf(String amount) {
        for (int i = 1; i <= 10; i++) {
            seedExpense(amount, "2026-06-" + String.format("%02d", i) + "T08:00:00Z");
        }
    }

    private void seedExpense(String amount, String occurredAt) {
        Instant when = Instant.parse(occurredAt);
        LocalDate day = when.atOffset(ZoneOffset.UTC).toLocalDate();
        expenseRepository.save(new ExpenseObservation(
                UUID.randomUUID(), USER, 7L, new BigDecimal(amount), CUR, when, day));
    }

    private TransactionCreatedEvent expense(UUID txId, String amount, String occurredAt) {
        LocalDate day = Instant.parse(occurredAt).atOffset(ZoneOffset.UTC).toLocalDate();
        return new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", occurredAt,
                txId, USER, "EXPENSE", new BigDecimal(amount),
                CUR, 7L, day.toString(), 1L);
    }
}
