package com.pm.dashboardservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Subset of transaction-service's TransactionResponse needed by the dashboard. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionDto(
        UUID id,
        String type,
        BigDecimal amount,
        String currency,
        Long categoryId,
        String description,
        LocalDate transactionDate) {
}
