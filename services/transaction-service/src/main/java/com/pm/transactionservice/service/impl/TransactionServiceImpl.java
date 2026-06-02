package com.pm.transactionservice.service.impl;

import com.pm.transactionservice.dto.CreateTransactionRequest;
import com.pm.transactionservice.dto.TransactionFilterRequest;
import com.pm.transactionservice.dto.TransactionResponse;
import com.pm.transactionservice.dto.UpdateTransactionRequest;
import com.pm.transactionservice.entity.Transaction;
import com.pm.transactionservice.exception.CategoryNotFoundException;
import com.pm.transactionservice.exception.InvalidTransactionDataException;
import com.pm.transactionservice.exception.TransactionNotFoundException;
import com.pm.transactionservice.repository.CategoryRepository;
import com.pm.transactionservice.repository.TransactionRepository;
import com.pm.transactionservice.repository.TransactionSpecifications;
import com.pm.transactionservice.service.TransactionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;

    public TransactionServiceImpl(TransactionRepository transactionRepository,
                                  CategoryRepository categoryRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional
    public TransactionResponse create(Long userId, CreateTransactionRequest request) {
        validateAmountPositive(request.getAmount());
        validateCategoryExists(request.getCategoryId());

        Transaction transaction = Transaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .type(request.getType())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .categoryId(request.getCategoryId())
                .description(request.getDescription())
                .transactionDate(request.getTransactionDate())
                .walletId(request.getWalletId())
                .isDeleted(false)
                .metadata(request.getMetadata())
                .build();

        Transaction saved = transactionRepository.save(transaction);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(Long userId, TransactionFilterRequest filter) {
        // page is 1-based in the API, 0-based in Spring Data.
        // Secondary sort on the (unique) id makes paging deterministic when several
        // rows share the same transactionDate — otherwise a tied row could repeat or
        // be skipped between pages.
        Sort sort = Sort.by(Sort.Direction.DESC, "transactionDate")
                .and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageRequest.of(filter.getPage() - 1, filter.getLimit(), sort);

        Specification<Transaction> spec =
                TransactionSpecifications.forUserWithFilters(userId, filter);

        return transactionRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionResponse getById(Long userId, UUID id) {
        Transaction transaction = transactionRepository
                .findByIdAndUserIdAndIsDeletedFalse(id, userId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found"));
        return toResponse(transaction);
    }

    @Override
    @Transactional
    public TransactionResponse update(Long userId, UUID id, UpdateTransactionRequest request) {
        Transaction transaction = transactionRepository
                .findByIdAndUserIdAndIsDeletedFalse(id, userId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found"));

        if (request.getType() != null) {
            transaction.setType(request.getType());
        }
        if (request.getAmount() != null) {
            validateAmountPositive(request.getAmount());
            transaction.setAmount(request.getAmount());
        }
        if (request.getCurrency() != null) {
            transaction.setCurrency(request.getCurrency());
        }
        if (request.getCategoryId() != null) {
            validateCategoryExists(request.getCategoryId());
            transaction.setCategoryId(request.getCategoryId());
        }
        if (request.getDescription() != null) {
            transaction.setDescription(request.getDescription());
        }
        if (request.getTransactionDate() != null) {
            transaction.setTransactionDate(request.getTransactionDate());
        }
        if (request.getWalletId() != null) {
            transaction.setWalletId(request.getWalletId());
        }
        if (request.getMetadata() != null) {
            transaction.setMetadata(request.getMetadata());
        }

        Transaction saved = transactionRepository.save(transaction);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long userId, UUID id) {
        Transaction transaction = transactionRepository
                .findByIdAndUserIdAndIsDeletedFalse(id, userId)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found"));

        // Soft delete.
        transaction.setDeleted(true);
        transactionRepository.save(transaction);
    }

    private void validateAmountPositive(BigDecimal amount) {
        // Service-level guard mirroring the DB CHECK (amount > 0). Protects callers
        // that bypass bean validation and turns the DB violation into a 400, not a 500.
        if (amount != null && amount.signum() <= 0) {
            throw new InvalidTransactionDataException("amount must be greater than 0");
        }
    }

    private void validateCategoryExists(Long categoryId) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new CategoryNotFoundException("Category " + categoryId + " does not exist");
        }
    }

    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .userId(t.getUserId())
                .type(t.getType())
                .amount(t.getAmount())
                .currency(t.getCurrency())
                .categoryId(t.getCategoryId())
                .description(t.getDescription())
                .transactionDate(t.getTransactionDate())
                .walletId(t.getWalletId())
                .metadata(t.getMetadata())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
