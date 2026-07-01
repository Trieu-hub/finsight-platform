package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.WalletType;
import com.pm.transactionservice.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * NOTE: userId is intentionally absent. It is resolved from the JWT, never the body.
 */
@Getter
@Setter
public class CreateWalletRequest {

    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    @NotNull(message = "type is required")
    private WalletType type;

    @NotNull(message = "currency is required")
    @ValidCurrency
    private String currency;

    /** Opening balance; defaults to 0 when omitted. Must not be negative. */
    @DecimalMin(value = "0.0", message = "initialBalance must not be negative")
    private BigDecimal initialBalance;
}
