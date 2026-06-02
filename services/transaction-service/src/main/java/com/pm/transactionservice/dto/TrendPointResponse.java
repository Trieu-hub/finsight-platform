package com.pm.transactionservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of the trend series for chart rendering. */
@Getter
@AllArgsConstructor
public class TrendPointResponse {

    private final LocalDate date;
    private final BigDecimal income;
    private final BigDecimal expense;
    private final BigDecimal balance;
}
