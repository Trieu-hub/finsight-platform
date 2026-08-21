package com.pm.transactionservice.service.impl;

import com.pm.transactionservice.dto.CreateTransactionRequest;
import com.pm.transactionservice.dto.ImportResultResponse;
import com.pm.transactionservice.dto.ImportRowError;
import com.pm.transactionservice.dto.TransactionFilterRequest;
import com.pm.transactionservice.dto.TransactionResponse;
import com.pm.transactionservice.dto.UpdateTransactionRequest;
import com.pm.transactionservice.audit.AuditLog;
import com.pm.transactionservice.entity.Category;
import com.pm.transactionservice.entity.Transaction;
import com.pm.transactionservice.enums.TransactionType;
import com.pm.transactionservice.event.TransactionCreatedEvent;
import com.pm.transactionservice.event.TransactionDeletedEvent;
import com.pm.transactionservice.event.TransactionUpdatedEvent;
import com.pm.transactionservice.exception.CategoryNotFoundException;
import com.pm.transactionservice.exception.InvalidTransactionDataException;
import com.pm.transactionservice.exception.TransactionNotFoundException;
import com.pm.transactionservice.repository.CategoryRepository;
import com.pm.transactionservice.repository.TransactionRepository;
import com.pm.transactionservice.repository.TransactionSpecifications;
import com.pm.transactionservice.service.TransactionService;
import com.pm.transactionservice.service.WalletService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLog auditLog;
    private final WalletService walletService;
    private final TransactionTemplate transactionTemplate;

    public TransactionServiceImpl(TransactionRepository transactionRepository,
                                  CategoryRepository categoryRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  AuditLog auditLog,
                                  WalletService walletService,
                                  PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.eventPublisher = eventPublisher;
        this.auditLog = auditLog;
        this.walletService = walletService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public TransactionResponse create(Long userId, CreateTransactionRequest request) {
        String clientRequestId = blankToNull(request.getClientRequestId());
        if (clientRequestId == null) {
            return toResponse(write(userId, request, null));
        }

        // A replay — the SPA queued this write offline, or never saw the response to the first
        // attempt. Return what that attempt created instead of creating a second transaction the
        // user would then have to find and delete.
        //
        // The unique index on (user_id, client_request_id) is the real guarantee; this check just
        // makes the ordinary sequential replay cheap. Two replays arriving *simultaneously* can
        // both pass it, and the loser gets a constraint violation — deliberately not caught here,
        // because the catch would run inside a transaction already marked rollback-only and could
        // not read anything. The client retries, this check finds the row, and the outcome is the
        // same. What can never happen is two rows.
        Optional<Transaction> alreadyWritten =
                transactionRepository.findByUserIdAndClientRequestId(userId, clientRequestId);
        return alreadyWritten
                .map(this::toResponse)
                .orElseGet(() -> toResponse(write(userId, request, null)));
    }

    /** Treats "" and "   " as absent, so a client sending an empty field does not claim the index. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The one path that turns a request into a stored transaction — used by {@link #create} and by
     * each row of an import, so an imported transaction goes through exactly the same validation,
     * event and wallet handling as one typed in by hand.
     *
     * <p>{@code importFingerprint} is null for everything but an import.
     */
    private Transaction write(Long userId, CreateTransactionRequest request, String importFingerprint) {
        validateAmountPositive(request.getAmount());
        validateCategoryForType(request.getCategoryId(), request.getType());
        validateTransferWallets(request.getType(), request.getWalletId(), request.getToWalletId());
        // Wallet(s) referenced must exist, belong to the user and hold this currency.
        walletService.validateForTransaction(userId, request.getType(), request.getCurrency(),
                request.getWalletId(), request.getToWalletId());

        boolean isTransfer = request.getType() == TransactionType.TRANSFER;
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
                // toWalletId is only meaningful for a TRANSFER; keep it null otherwise.
                .toWalletId(isTransfer ? request.getToWalletId() : null)
                // A budget attribution only applies to an EXPENSE; ignore it for INCOME/TRANSFER.
                .budgetId(request.getType() == TransactionType.EXPENSE ? request.getBudgetId() : null)
                .isDeleted(false)
                .metadata(request.getMetadata())
                .importFingerprint(importFingerprint)
                // Null for an ordinary create; set when the client is replaying a queued write.
                .clientRequestId(blankToNull(request.getClientRequestId()))
                .build();

        Transaction saved = transactionRepository.save(transaction);

        // Emit the domain event inside the transaction; TransactionEventListener forwards
        // it to Kafka only AFTER_COMMIT, so a rolled-back create never publishes.
        eventPublisher.publishEvent(TransactionCreatedEvent.of(
                saved.getId(),
                saved.getUserId(),
                saved.getType(),
                saved.getAmount(),
                saved.getCurrency(),
                saved.getCategoryId(),
                saved.getTransactionDate(),
                saved.getWalletId(),
                saved.getBudgetId()));

        // Credit/debit the affected wallet(s) atomically, in this same DB transaction.
        walletService.applyTransactionEffect(userId, saved.getType(), saved.getAmount(),
                saved.getWalletId(), saved.getToWalletId(), +1);

        auditLog.record("CREATE", "transaction", saved.getId(), userId);
        return saved;
    }

    /**
     * Writes a parsed statement, row by row.
     *
     * <p>Deliberately NOT {@code @Transactional}: each row commits on its own through
     * {@link #transactionTemplate}, so a row the service refuses neither stops the rows after it
     * nor undoes the ones already written — and because every written row carries a fingerprint,
     * re-uploading the file after a partial import skips what landed instead of doubling it.
     */
    @Override
    public ImportResultResponse importTransactions(Long userId, List<CreateTransactionRequest> rows) {
        List<ImportRowError> errors = new ArrayList<>();
        // A statement that lists the same line twice is the file's problem, not the DB's: catch it
        // here, because both copies would otherwise race for the same fingerprint.
        Set<String> seenInThisFile = new HashSet<>();
        int imported = 0;
        int duplicates = 0;

        for (int i = 0; i < rows.size(); i++) {
            CreateTransactionRequest row = rows.get(i);
            String fingerprint = fingerprint(userId, row);
            if (!seenInThisFile.add(fingerprint)) {
                duplicates++;
                continue;
            }
            try {
                boolean written = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                    if (transactionRepository.existsByUserIdAndImportFingerprint(userId, fingerprint)) {
                        return false;
                    }
                    write(userId, row, fingerprint);
                    return true;
                }));
                if (written) {
                    imported++;
                } else {
                    duplicates++;
                }
            } catch (DataIntegrityViolationException e) {
                // The unique index caught what the check-then-insert above cannot: the same file
                // submitted twice at once. Still a duplicate, not a failure.
                duplicates++;
            } catch (RuntimeException e) {
                errors.add(ImportRowError.builder()
                        .row(i + 1)
                        .message(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        .build());
            }
        }

        auditLog.record("IMPORT", "transaction", null, userId);
        return ImportResultResponse.builder()
                .imported(imported)
                .duplicates(duplicates)
                .errors(errors)
                .build();
    }

    /**
     * Identifies a statement line by what a bank actually prints on it. Recomputed from the row on
     * every import — the client never sends it — so it cannot be used to make one account's import
     * collide with another's.
     *
     * <p>The amount is normalised (1000.00 and 1000 are the same line) and the description trimmed,
     * because the same statement exported twice rarely comes back byte-identical.
     */
    private static String fingerprint(Long userId, CreateTransactionRequest row) {
        String description = row.getDescription() == null ? "" : row.getDescription().trim();
        String amount = row.getAmount() == null
                ? "" : row.getAmount().stripTrailingZeros().toPlainString();
        String material = userId + "|" + row.getType() + "|" + amount + "|" + row.getCurrency()
                + "|" + row.getTransactionDate() + "|" + description;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE implementation; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

        // Snapshot the pre-update state so it can be reversed before the new one is applied —
        // both the wallet effect (walletId/toWalletId) and the budget-matching attributes
        // (type/amount/currency/categoryId/transactionDate) carried on the TransactionUpdated event.
        TransactionType oldType = transaction.getType();
        BigDecimal oldAmount = transaction.getAmount();
        Long oldWalletId = transaction.getWalletId();
        Long oldToWalletId = transaction.getToWalletId();
        String oldCurrency = transaction.getCurrency();
        Long oldCategoryId = transaction.getCategoryId();
        LocalDate oldTransactionDate = transaction.getTransactionDate();
        UUID oldBudgetId = transaction.getBudgetId();

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
        if (request.getToWalletId() != null) {
            transaction.setToWalletId(request.getToWalletId());
        }
        if (request.getBudgetId() != null) {
            transaction.setBudgetId(request.getBudgetId());
        }
        if (request.getMetadata() != null) {
            transaction.setMetadata(request.getMetadata());
        }

        // Re-validate the (type, category) pair when either side changed, against the
        // resulting state — so a partial update can't end up with, e.g., an INCOME
        // category on an EXPENSE transaction.
        if (request.getType() != null || request.getCategoryId() != null) {
            validateCategoryForType(transaction.getCategoryId(), transaction.getType());
        }

        // Keep the transfer invariant on the resulting state: a TRANSFER needs a distinct
        // source/destination wallet; a non-TRANSFER carries no destination wallet.
        if (transaction.getType() == TransactionType.TRANSFER) {
            validateTransferWallets(transaction.getType(),
                    transaction.getWalletId(), transaction.getToWalletId());
        } else {
            transaction.setToWalletId(null);
        }

        // A budget attribution only applies to an EXPENSE; clear it if the resulting type isn't one.
        if (transaction.getType() != TransactionType.EXPENSE) {
            transaction.setBudgetId(null);
        }

        // Validate the resulting wallet state, then move the balances: undo the old effect and
        // apply the new one (both atomic, same DB transaction) so a net change is reflected once.
        walletService.validateForTransaction(userId, transaction.getType(), transaction.getCurrency(),
                transaction.getWalletId(), transaction.getToWalletId());

        Transaction saved = transactionRepository.save(transaction);

        // Emit the domain event inside the transaction; TransactionEventListener forwards it
        // to Kafka only AFTER_COMMIT. Carries old + new so a consumer materializing a running
        // total (budget spent_amount) reverses the old contribution and applies the new one.
        eventPublisher.publishEvent(TransactionUpdatedEvent.of(
                saved.getId(),
                userId,
                oldType, oldAmount, oldCurrency, oldCategoryId, oldTransactionDate, oldBudgetId,
                saved.getType(), saved.getAmount(), saved.getCurrency(),
                saved.getCategoryId(), saved.getTransactionDate(), saved.getBudgetId()));

        walletService.applyTransactionEffect(userId, oldType, oldAmount, oldWalletId, oldToWalletId, -1);
        walletService.applyTransactionEffect(userId, saved.getType(), saved.getAmount(),
                saved.getWalletId(), saved.getToWalletId(), +1);

        auditLog.record("UPDATE", "transaction", saved.getId(), userId);
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
        // Release the statement line back: a deleted row is invisible to the user, so leaving its
        // fingerprint behind would make re-importing the statement silently skip it forever.
        transaction.setImportFingerprint(null);
        transactionRepository.save(transaction);

        // Emit AFTER_COMMIT so a running-total consumer (budget spent_amount) reverses this
        // transaction's contribution. The soft delete leaves every other field intact.
        eventPublisher.publishEvent(TransactionDeletedEvent.of(
                transaction.getId(),
                userId,
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getCategoryId(),
                transaction.getTransactionDate(),
                transaction.getBudgetId()));

        // Undo this transaction's effect on wallet balance(s).
        walletService.applyTransactionEffect(userId, transaction.getType(), transaction.getAmount(),
                transaction.getWalletId(), transaction.getToWalletId(), -1);

        auditLog.record("DELETE", "transaction", id, userId);
    }

    private void validateAmountPositive(BigDecimal amount) {
        // Service-level guard mirroring the DB CHECK (amount > 0). Protects callers
        // that bypass bean validation and turns the DB violation into a 400, not a 500.
        if (amount != null && amount.signum() <= 0) {
            throw new InvalidTransactionDataException("amount must be greater than 0");
        }
    }

    private void validateTransferWallets(TransactionType type, Long walletId, Long toWalletId) {
        // Only TRANSFER carries wallet-to-wallet semantics. INCOME/EXPENSE ignore toWalletId.
        if (type != TransactionType.TRANSFER) {
            return;
        }
        if (walletId == null || toWalletId == null) {
            throw new InvalidTransactionDataException(
                    "A TRANSFER requires both walletId (source) and toWalletId (destination)");
        }
        if (walletId.equals(toWalletId)) {
            throw new InvalidTransactionDataException(
                    "A TRANSFER's source and destination wallet must be different");
        }
    }

    private void validateCategoryForType(Long categoryId, TransactionType type) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(
                        "Category " + categoryId + " does not exist"));
        // A category is INCOME or EXPENSE; it must match the transaction type, so an
        // INCOME category (e.g. Salary) can't be attached to an EXPENSE transaction.
        if (type != null && category.getType() != type) {
            throw new InvalidTransactionDataException(
                    "Category '" + category.getName() + "' is a " + category.getType()
                    + " category and cannot be used for a " + type + " transaction");
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
                .toWalletId(t.getToWalletId())
                .budgetId(t.getBudgetId())
                .metadata(t.getMetadata())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
