package com.pm.transactionservice.service.impl;

import com.pm.transactionservice.audit.AuditLog;
import com.pm.transactionservice.dto.CreateWalletRequest;
import com.pm.transactionservice.dto.UpdateWalletRequest;
import com.pm.transactionservice.dto.WalletResponse;
import com.pm.transactionservice.entity.Wallet;
import com.pm.transactionservice.enums.TransactionType;
import com.pm.transactionservice.exception.InvalidTransactionDataException;
import com.pm.transactionservice.exception.WalletNotEmptyException;
import com.pm.transactionservice.exception.WalletNotFoundException;
import com.pm.transactionservice.repository.WalletRepository;
import com.pm.transactionservice.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final AuditLog auditLog;

    public WalletServiceImpl(WalletRepository walletRepository, AuditLog auditLog) {
        this.walletRepository = walletRepository;
        this.auditLog = auditLog;
    }

    @Override
    @Transactional
    public WalletResponse create(Long userId, CreateWalletRequest request) {
        Wallet wallet = Wallet.builder()
                .userId(userId)
                .name(request.getName())
                .type(request.getType())
                .currency(request.getCurrency())
                .balance(request.getInitialBalance() != null ? request.getInitialBalance() : BigDecimal.ZERO)
                .isDeleted(false)
                .build();

        Wallet saved = walletRepository.save(wallet);
        auditLog.record("CREATE", "wallet", saved.getId(), userId);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WalletResponse> list(Long userId) {
        return walletRepository.findByUserIdAndIsDeletedFalseOrderByIdAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WalletResponse getById(Long userId, Long id) {
        return toResponse(requireOwned(userId, id));
    }

    @Override
    @Transactional
    public WalletResponse update(Long userId, Long id, UpdateWalletRequest request) {
        Wallet wallet = requireOwned(userId, id);
        if (request.getName() != null) {
            wallet.setName(request.getName());
        }
        if (request.getType() != null) {
            wallet.setType(request.getType());
        }
        Wallet saved = walletRepository.save(wallet);
        auditLog.record("UPDATE", "wallet", saved.getId(), userId);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long id) {
        Wallet wallet = requireOwned(userId, id);
        // A wallet still holding money must be emptied (spent or transferred out) before removal,
        // so a deletion can never silently discard a balance.
        if (wallet.getBalance().signum() != 0) {
            throw new WalletNotEmptyException(
                    "Wallet '" + wallet.getName() + "' still has a non-zero balance and cannot be deleted");
        }
        wallet.setDeleted(true);
        walletRepository.save(wallet);
        auditLog.record("DELETE", "wallet", id, userId);
    }

    // --- Transaction integration -------------------------------------------------

    @Override
    public void validateForTransaction(Long userId, TransactionType type, String currency,
                                       Long walletId, Long toWalletId) {
        if (type == TransactionType.TRANSFER) {
            requireUsable(userId, walletId, currency);
            requireUsable(userId, toWalletId, currency);
        } else if (walletId != null) {
            requireUsable(userId, walletId, currency);
        }
    }

    @Override
    public void applyTransactionEffect(Long userId, TransactionType type, BigDecimal amount,
                                       Long walletId, Long toWalletId, int factor) {
        BigDecimal signed = amount.multiply(BigDecimal.valueOf(factor));
        switch (type) {
            case INCOME -> {
                if (walletId != null) {
                    adjust(userId, walletId, signed);
                }
            }
            case EXPENSE -> {
                if (walletId != null) {
                    adjust(userId, walletId, signed.negate());
                }
            }
            case TRANSFER -> {
                adjust(userId, walletId, signed.negate());  // debit source
                adjust(userId, toWalletId, signed);          // credit destination
            }
        }
    }

    // --- helpers -----------------------------------------------------------------

    private void adjust(Long userId, Long walletId, BigDecimal delta) {
        // Best-effort atomic increment. The forward (apply) path always targets a wallet that
        // validateForTransaction has just confirmed exists; the reverse path tolerates a wallet
        // that was since emptied and deleted (0 rows changed) rather than failing the update.
        walletRepository.adjustBalance(walletId, userId, delta);
    }

    private void requireUsable(Long userId, Long walletId, String currency) {
        Wallet wallet = requireOwned(userId, walletId);
        if (!wallet.getCurrency().equals(currency)) {
            throw new InvalidTransactionDataException(
                    "Wallet '" + wallet.getName() + "' holds " + wallet.getCurrency()
                    + " and cannot be used for a " + currency + " transaction");
        }
    }

    private Wallet requireOwned(Long userId, Long id) {
        if (id == null) {
            throw new WalletNotFoundException("Wallet not found");
        }
        return walletRepository.findByIdAndUserIdAndIsDeletedFalse(id, userId)
                .orElseThrow(() -> new WalletNotFoundException("Wallet " + id + " not found"));
    }

    private WalletResponse toResponse(Wallet w) {
        return WalletResponse.builder()
                .id(w.getId())
                .userId(w.getUserId())
                .name(w.getName())
                .type(w.getType())
                .currency(w.getCurrency())
                .balance(w.getBalance())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }
}
