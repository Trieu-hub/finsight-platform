package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.TransactionType;
import com.pm.transactionservice.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * NOTE: userId is intentionally absent. It is resolved from the JWT, never the body.
 */
@Getter
@Setter
public class CreateTransactionRequest {

    @NotNull(message = "type is required")
    private TransactionType type;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
    @Digits(integer = 15, fraction = 4, message = "amount is out of range")
    private BigDecimal amount;

    @NotNull(message = "currency is required")
    @ValidCurrency
    private String currency;

    @NotNull(message = "categoryId is required")
    private Long categoryId;

    @Size(max = 500, message = "description must be at most 500 characters")
    private String description;

    @NotNull(message = "transactionDate is required")
    private LocalDate transactionDate;

    private Long walletId;

    /** Destination wallet for a TRANSFER (required, and must differ from walletId). */
    private Long toWalletId;

    /**
     * The budget this EXPENSE is charged against. Optional here (the frontend makes it required
     * when the category has a budget; the game omits it). Ignored for INCOME/TRANSFER. Opaque —
     * validated only for ownership downstream in budget-service, never cross-service here.
     */
    private UUID budgetId;

    private Map<String, Object> metadata;
}
