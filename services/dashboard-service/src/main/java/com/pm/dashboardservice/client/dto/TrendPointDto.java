package com.pm.dashboardservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One DAILY point of transaction-service's trend series (date + income/expense/balance). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrendPointDto(
        LocalDate date,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance) {
}
