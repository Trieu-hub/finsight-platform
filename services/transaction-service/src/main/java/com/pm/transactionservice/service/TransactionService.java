package com.pm.transactionservice.service;

import com.pm.transactionservice.dto.CreateTransactionRequest;
import com.pm.transactionservice.dto.ImportResultResponse;
import com.pm.transactionservice.dto.TransactionFilterRequest;
import com.pm.transactionservice.dto.TransactionResponse;
import com.pm.transactionservice.dto.UpdateTransactionRequest;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public interface TransactionService {

    TransactionResponse create(Long userId, CreateTransactionRequest request);

    /**
     * Writes a parsed statement. Rows are independent: the result reports what was written, what
     * was already there, and which rows were refused and why.
     */
    ImportResultResponse importTransactions(Long userId, List<CreateTransactionRequest> rows);

    Page<TransactionResponse> list(Long userId, TransactionFilterRequest filter);

    TransactionResponse getById(Long userId, UUID id);

    TransactionResponse update(Long userId, UUID id, UpdateTransactionRequest request);

    void delete(Long userId, UUID id);
}
