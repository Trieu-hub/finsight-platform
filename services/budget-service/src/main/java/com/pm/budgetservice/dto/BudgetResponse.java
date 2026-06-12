package com.pm.budgetservice.dto;

import com.pm.budgetservice.enums.BudgetPeriod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class BudgetResponse {

    private final UUID id;
    private final Long userId;
    private final String name;
    private final Long categoryId;
    private final BudgetPeriod periodType;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final BigDecimal limitAmount;
    /** Materialized spend (eventually consistent — see docs/ADR-0004). */
    private final BigDecimal spentAmount;
    private final String currency;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
