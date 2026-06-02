package com.pm.transactionservice.repository.projection;

import com.pm.transactionservice.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Daily sum of amounts per type, used to assemble the trend series. */
public interface DailyTypeAggregate {

    LocalDate getEntryDate();

    TransactionType getTransactionType();

    BigDecimal getTotal();
}
