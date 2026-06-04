package com.pm.budgetservice.dto;

import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Partial update: only non-null fields are applied. categoryId/period/dates may be
 * changed, but userId can never be set from the body.
 */
@Getter
@Setter
public class UpdateBudgetRequest {

    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    private Long categoryId;

    private BudgetPeriod periodType;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    @DecimalMin(value = "0.0", inclusive = false, message = "limitAmount must be greater than 0")
    private BigDecimal limitAmount;

    @ValidCurrency
    private String currency;
}
