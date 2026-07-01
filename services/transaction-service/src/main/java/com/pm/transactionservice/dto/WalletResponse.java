package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.WalletType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class WalletResponse {

    private Long id;
    private Long userId;
    private String name;
    private WalletType type;
    private String currency;
    private BigDecimal balance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
