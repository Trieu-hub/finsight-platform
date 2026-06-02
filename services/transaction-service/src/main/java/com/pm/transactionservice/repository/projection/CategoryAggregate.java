package com.pm.transactionservice.repository.projection;

import com.pm.transactionservice.enums.TransactionType;

import java.math.BigDecimal;

/** Per-category totals for the spending breakdown. */
public interface CategoryAggregate {

    Long getCategoryId();

    String getCategoryName();

    TransactionType getTransactionType();

    BigDecimal getTotal();

    long getEntryCount();
}
