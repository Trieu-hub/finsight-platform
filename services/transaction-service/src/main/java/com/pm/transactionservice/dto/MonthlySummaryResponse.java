package com.pm.transactionservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** { "income": number, "expense": number, "balance": number } */
@Getter
@AllArgsConstructor
public class MonthlySummaryResponse {

    private final BigDecimal income;
    private final BigDecimal expense;
    private final BigDecimal balance;
}
