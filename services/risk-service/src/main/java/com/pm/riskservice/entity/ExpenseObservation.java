package com.pm.riskservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A consumed transaction, recorded so the windowed risk rules (RAPID_SPENDING,
 * LARGE_DAILY_SPEND) and the behavioral insights can be evaluated with SQL count/sum over a
 * user's recent activity. The {@code id} is the source {@code TransactionCreated} event id,
 * which makes recording idempotent (a redelivered event is not double-counted).
 *
 * <p>Originally EXPENSE-only; from Phase E.3 INCOME rows are also recorded (discriminated by
 * {@link #transactionType}) so LOW_SAVINGS_RATE can compare monthly income against expenses.
 * The risk rules and the pre-E.3 insights filter to {@code EXPENSE} and are unaffected. Plain
 * JPA, no Lombok.
 */
@Entity
@Table(name = "observed_expenses")
public class ExpenseObservation {

    /** Discriminates income from expense rows; matches the source event's {@code type}. */
    public static final String EXPENSE = "EXPENSE";
    public static final String INCOME = "INCOME";

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** {@code EXPENSE} or {@code INCOME} — the source transaction's type. */
    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    /** Category of the expense (from the source event); enables the per-category insights. */
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO 4217 currency; used for budget-exact matching (BUDGET_RISK). */
    @Column(name = "currency", length = 3)
    private String currency;

    /** Event time — basis for the 10-minute RAPID_SPENDING window. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Calendar day — basis for the LARGE_DAILY_SPEND daily total. */
    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    protected ExpenseObservation() {
        // JPA
    }

    /** Records an EXPENSE observation (the original, expense-only constructor). */
    public ExpenseObservation(UUID id, Long userId, Long categoryId, BigDecimal amount,
                              String currency, Instant occurredAt, LocalDate transactionDate) {
        this(id, userId, EXPENSE, categoryId, amount, currency, occurredAt, transactionDate);
    }

    /** Records an observation of the given {@code transactionType} ({@code EXPENSE}/{@code INCOME}). */
    public ExpenseObservation(UUID id, Long userId, String transactionType, Long categoryId,
                              BigDecimal amount, String currency, Instant occurredAt,
                              LocalDate transactionDate) {
        this.id = id;
        this.userId = userId;
        this.transactionType = transactionType;
        this.categoryId = categoryId;
        this.amount = amount;
        this.currency = currency;
        this.occurredAt = occurredAt;
        this.transactionDate = transactionDate;
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public LocalDate getTransactionDate() {
        return transactionDate;
    }
}
