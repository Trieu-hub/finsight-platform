package com.pm.dashboardservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** transaction-service monthly summary: income / expense / balance. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MonthlySummaryDto(
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance) {
}
