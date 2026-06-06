package com.pm.dashboardservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** One row of transaction-service's spend-by-category breakdown. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategorySummaryDto(
        Long categoryId,
        String categoryName,
        String type,
        BigDecimal total,
        long count) {
}
