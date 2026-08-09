package com.pm.transactionservice.service;

import com.pm.transactionservice.dto.TransactionFilterRequest;

public interface TransactionExportService {

    /**
     * Renders the caller's transactions matching {@code filter} as a CSV document. The filter is
     * the same one the list endpoint takes, minus paging: an export is of everything that matches,
     * up to a bound the implementation documents.
     */
    String exportCsv(Long userId, TransactionFilterRequest filter);
}
