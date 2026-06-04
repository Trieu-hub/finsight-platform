package com.pm.budgetservice.dto;

import com.pm.budgetservice.enums.BudgetPeriod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Bound from query string for GET /budgets.
 * Supports pagination plus category / period / active-on filters.
 */
@Getter
@Setter
public class BudgetFilterRequest {

    @Min(value = 1, message = "page must be at least 1")
    private int page = 1;

    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = 100, message = "limit must be at most 100")
    private int limit = 10;

    private Long categoryId;

    private BudgetPeriod periodType;

    /** When set, returns only budgets whose [startDate, endDate] range contains this date. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate activeOn;
}
