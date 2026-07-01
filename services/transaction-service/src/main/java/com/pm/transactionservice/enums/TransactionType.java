package com.pm.transactionservice.enums;

public enum TransactionType {
    INCOME,
    EXPENSE,
    /** A wallet-to-wallet move: neither income nor expense. Ignored by budget/risk/analytics. */
    TRANSFER
}
