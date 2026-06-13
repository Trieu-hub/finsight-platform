package com.pm.riskservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pm.riskservice.entity.RiskAlert;
import com.pm.riskservice.repository.RiskAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level tests for the read API over persisted risk alerts (Phase D.2): listing
 * (newest first) and fetch-by-id, including the 404 path.
 */
class RiskAlertApiIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Autowired
    private RiskAlertRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void listReturnsAlertsNewestFirst() throws Exception {
        Instant now = Instant.now();
        RiskAlert older = save(101L, now.minus(2, ChronoUnit.HOURS));
        RiskAlert newer = save(102L, now.minus(1, ChronoUnit.HOURS));

        String body = mockMvc.perform(get("/api/v1/risks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode arr = objectMapper.readTree(body);
        // Newest (newer) first.
        assertThat(arr.get(0).path("id").asText()).isEqualTo(newer.getId().toString());
        assertThat(arr.get(0).path("userId").asLong()).isEqualTo(102L);
        assertThat(arr.get(0).path("riskType").asText()).isEqualTo("HIGH_AMOUNT_EXPENSE");
        assertThat(arr.get(0).path("riskSeverity").asText()).isEqualTo("HIGH");
        assertThat(arr.get(1).path("id").asText()).isEqualTo(older.getId().toString());
    }

    @Test
    void listIsEmptyWhenNoAlerts() throws Exception {
        mockMvc.perform(get("/api/v1/risks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getByIdReturnsTheAlert() throws Exception {
        RiskAlert alert = save(200L, Instant.now());

        mockMvc.perform(get("/api/v1/risks/{id}", alert.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alert.getId().toString()))
                .andExpect(jsonPath("$.userId").value(200))
                .andExpect(jsonPath("$.transactionId").value(alert.getTransactionId().toString()))
                .andExpect(jsonPath("$.riskType").value("HIGH_AMOUNT_EXPENSE"))
                .andExpect(jsonPath("$.riskSeverity").value("HIGH"));
    }

    @Test
    void getByUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/risks/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private RiskAlert save(long userId, Instant occurredAt) {
        RiskAlert alert = new RiskAlert(UUID.randomUUID(), userId, UUID.randomUUID(),
                "HIGH_AMOUNT_EXPENSE", "HIGH", occurredAt, Instant.now());
        return repository.save(alert);
    }
}
