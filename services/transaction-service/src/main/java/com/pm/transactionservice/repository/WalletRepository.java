package com.pm.transactionservice.repository;

import com.pm.transactionservice.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    List<Wallet> findByUserIdAndIsDeletedFalseOrderByIdAsc(Long userId);

    Optional<Wallet> findByIdAndUserIdAndIsDeletedFalse(Long id, Long userId);

    /**
     * Atomically adds {@code delta} (may be negative) to a wallet's balance. Done as a single
     * SQL statement so concurrent transaction writes cannot lose an update (no read-modify-write
     * in application code). Scoped by userId + not-deleted; returns the number of rows changed
     * (0 when the wallet does not exist / is deleted / belongs to another user).
     */
    @Modifying
    @Query("""
            UPDATE Wallet w
               SET w.balance = w.balance + :delta,
                   w.updatedAt = CURRENT_TIMESTAMP
             WHERE w.id = :id AND w.userId = :userId AND w.isDeleted = false
            """)
    int adjustBalance(@Param("id") Long id, @Param("userId") Long userId, @Param("delta") BigDecimal delta);
}
