package com.pm.transactionservice.service;

import com.pm.transactionservice.dto.CreateTransactionRequest;
import com.pm.transactionservice.dto.TransactionFilterRequest;
import com.pm.transactionservice.dto.TransactionResponse;
import com.pm.transactionservice.dto.UpdateTransactionRequest;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface TransactionService {

    TransactionResponse create(Long userId, CreateTransactionRequest request);

    Page<TransactionResponse> list(Long userId, TransactionFilterRequest filter);

    TransactionResponse getById(Long userId, UUID id);

    TransactionResponse update(Long userId, UUID id, UpdateTransactionRequest request);

    void delete(Long userId, UUID id);
}
