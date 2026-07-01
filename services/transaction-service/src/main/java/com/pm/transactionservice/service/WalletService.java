package com.pm.transactionservice.service;

import com.pm.transactionservice.dto.CreateWalletRequest;
import com.pm.transactionservice.dto.UpdateWalletRequest;
import com.pm.transactionservice.dto.WalletResponse;
import com.pm.transactionservice.enums.TransactionType;

import java.math.BigDecimal;
import java.util.List;

public interface WalletService {

    WalletResponse create(Long userId, CreateWalletRequest request);

    List<WalletResponse> list(Long userId);

    WalletResponse getById(Long userId, Long id);

    WalletResponse update(Long userId, Long id, UpdateWalletRequest request);

    void delete(Long userId, Long id);

    // --- Transaction integration (invoked inside the transaction service's DB transaction) ---

    /**
     * Validates that the wallet(s) a transaction references exist, belong to the user and hold
     * the transaction's currency. A no-op when the transaction carries no wallet.
     */
    void validateForTransaction(Long userId, TransactionType type, String currency,
                                Long walletId, Long toWalletId);

    /**
     * Applies ({@code factor = +1}) or reverses ({@code factor = -1}) a transaction's effect on
     * the affected wallet balance(s): INCOME credits, EXPENSE debits, TRANSFER moves from source
     * to destination. A no-op when the transaction carries no wallet.
     */
    void applyTransactionEffect(Long userId, TransactionType type, BigDecimal amount,
                                Long walletId, Long toWalletId, int factor);
}
