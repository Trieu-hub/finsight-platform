package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.WalletType;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Partial update. All fields optional; only non-null fields are applied.
 * Currency and balance are deliberately NOT editable here: currency cannot change once a
 * wallet holds a balance, and balance is derived only from transactions.
 */
@Getter
@Setter
public class UpdateWalletRequest {

    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    private WalletType type;
}
