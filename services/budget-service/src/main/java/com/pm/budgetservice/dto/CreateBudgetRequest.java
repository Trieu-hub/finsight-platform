package com.pm.budgetservice.dto;

import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * NOTE: userId is intentionally absent. It is resolved from the JWT, never the body.
 */
@Getter
@Setter
public class CreateBudgetRequest {

    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    @NotNull(message = "categoryId is required")
    private Long categoryId;

    @NotNull(message = "periodType is required")
    private BudgetPeriod periodType;

    @NotNull(message = "startDate is required")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @NotNull(message = "endDate is required")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    @NotNull(message = "limitAmount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "limitAmount must be greater than 0")
    private BigDecimal limitAmount;

    @NotNull(message = "currency is required")
    @ValidCurrency
    private String currency;
}
