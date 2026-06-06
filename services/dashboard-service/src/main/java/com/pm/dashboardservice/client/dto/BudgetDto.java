package com.pm.dashboardservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Subset of budget-service's BudgetResponse needed by the dashboard. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BudgetDto(
        UUID id,
        String name,
        Long categoryId,
        String periodType,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal limitAmount,
        String currency) {
}
