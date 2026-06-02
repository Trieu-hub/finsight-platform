package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.TransactionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Bound from query string for GET /transactions.
 * Supports pagination, date range / type / category filters.
 */
@Getter
@Setter
public class TransactionFilterRequest {

    @Min(value = 1, message = "page must be at least 1")
    private int page = 1;

    @Min(value = 1, message = "limit must be at least 1")
    @Max(value = 100, message = "limit must be at most 100")
    private int limit = 10;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fromDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate toDate;

    private TransactionType type;

    private Long categoryId;
}
