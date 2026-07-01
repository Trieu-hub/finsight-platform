package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.TransactionType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class TransactionResponse {

    private UUID id;
    private Long userId;
    private TransactionType type;
    private BigDecimal amount;
    private String currency;
    private Long categoryId;
    private String description;
    private LocalDate transactionDate;
    private Long walletId;
    private Long toWalletId;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
