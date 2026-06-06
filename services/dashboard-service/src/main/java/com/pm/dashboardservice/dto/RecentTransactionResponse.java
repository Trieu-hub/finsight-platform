package com.pm.dashboardservice.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One row of the recent-transactions list (upstream {@code id} exposed as {@code transactionId}). */
public record RecentTransactionResponse(
        UUID transactionId,
        String type,
        BigDecimal amount,
        String currency,
        Long categoryId,
        String description,
        LocalDate transactionDate) {
}
