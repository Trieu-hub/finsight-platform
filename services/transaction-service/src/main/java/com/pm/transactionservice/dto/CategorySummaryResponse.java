package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.TransactionType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** One row of the spending-by-category breakdown. */
@Getter
@AllArgsConstructor
public class CategorySummaryResponse {

    private final Long categoryId;
    private final String categoryName;
    private final TransactionType type;
    private final BigDecimal total;
    private final long count;
}
