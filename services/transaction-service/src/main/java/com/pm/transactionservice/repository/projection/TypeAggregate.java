package com.pm.transactionservice.repository.projection;

import com.pm.transactionservice.enums.TransactionType;

import java.math.BigDecimal;

/** Sum of amounts per transaction type. */
public interface TypeAggregate {

    TransactionType getTransactionType();

    BigDecimal getTotal();
}
