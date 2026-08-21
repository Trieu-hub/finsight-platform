package com.pm.transactionservice.entity;

import com.pm.transactionservice.enums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    /** Owner of the transaction. Always sourced from the JWT, never the request body. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 currency code, e.g. "USD". */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /**
     * The budget this EXPENSE is charged against, chosen by the user at record time. An opaque
     * reference to budget-service's UUID key (no FK, cross-service — like {@link #walletId}).
     * Null for INCOME/TRANSFER and for expenses recorded without a budget (e.g. the game).
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "budget_id", length = 36)
    private UUID budgetId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "wallet_id")
    private Long walletId;

    /** Destination wallet for a TRANSFER (source is {@link #walletId}); null otherwise. */
    @Column(name = "to_wallet_id")
    private Long toWalletId;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean isDeleted = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "json")
    private Map<String, Object> metadata;

    /**
     * Identifies the statement line this row was imported from, so re-uploading the same file
     * skips it instead of duplicating it. Derived in the service from the row's own fields; null
     * for anything recorded by hand or by the game, and cleared on delete so a deleted row can be
     * imported again.
     */
    @Column(name = "import_fingerprint", length = 64)
    private String importFingerprint;

    /**
     * An opaque token the client attaches to one intended write, so replaying it — from the
     * offline outbox, or after a response was lost — resolves to this same row instead of a
     * second one. Null for an ordinary create; unique per user where present (V14).
     */
    @Column(name = "client_request_id", length = 64)
    private String clientRequestId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
