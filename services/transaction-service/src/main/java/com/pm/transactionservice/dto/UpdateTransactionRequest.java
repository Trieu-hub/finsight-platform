package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.TransactionType;
import com.pm.transactionservice.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Partial update. All fields are optional; only non-null fields are applied.
 * userId can never be changed and is never accepted here.
 */
@Getter
@Setter
public class UpdateTransactionRequest {

    private TransactionType type;

    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
    private BigDecimal amount;

    @ValidCurrency
    private String currency;

    private Long categoryId;

    @Size(max = 500, message = "description must be at most 500 characters")
    private String description;

    private LocalDate transactionDate;

    private Long walletId;

    private Long toWalletId;

    private Map<String, Object> metadata;
}
