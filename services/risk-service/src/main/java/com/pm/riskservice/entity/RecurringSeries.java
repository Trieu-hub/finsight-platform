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
 * A charge that keeps coming back (Phase G.1) — a subscription, a rent payment, a standing
 * bill. Recognised by (user, category, currency, roughly this amount) repeating on a cadence,
 * because the {@code TransactionCreated} contract carries no merchant or description.
 *
 * <p>Unlike {@code Insight} and {@code Anomaly}, which snapshot a moment, this row is a
 * living read-model: every matched charge moves {@code lastSeen}, {@code nextExpected} and
 * {@code occurrences}. Plain JPA, no Lombok, matching the rest of this service.
 *
 * <p>{@code typicalAmount} is the <em>established</em> price, not the last one seen. It only
 * moves when the detector flags a rise or records a fall, so a subscription that creeps up
 * 5% a month is still measured against what it originally cost.
 */
@Entity
@Table(name = "recurring_series")
public class RecurringSeries {

    /** The charge is still arriving on schedule. */
    public static final String ACTIVE = "ACTIVE";
    /** A sweep found the expected charge missing and reported it; the series is closed. */
    public static final String LAPSED = "LAPSED";

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "typical_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal typicalAmount;

    /** The cadence in days: 7 (weekly), 30 (monthly) or 91 (quarterly). */
    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    @Column(name = "first_seen", nullable = false)
    private LocalDate firstSeen;

    @Column(name = "last_seen", nullable = false)
    private LocalDate lastSeen;

    @Column(name = "next_expected", nullable = false)
    private LocalDate nextExpected;

    /** Opaque reference to a transaction-service transaction (not a FK — another service's DB). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "last_transaction_id", nullable = false, length = 36)
    private UUID lastTransactionId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecurringSeries() {
        // JPA
    }

    /**
     * Opens a series from the two charges that established it: {@code firstSeen} is the earlier
     * one, everything else describes the later one. {@code occurrences} therefore starts at 2.
     */
    public RecurringSeries(UUID id, Long userId, Long categoryId, String currency,
                           BigDecimal typicalAmount, int intervalDays, LocalDate firstSeen,
                           LocalDate lastSeen, UUID lastTransactionId, Instant now) {
        this.id = id;
        this.userId = userId;
        this.categoryId = categoryId;
        this.currency = currency;
        this.typicalAmount = typicalAmount;
        this.intervalDays = intervalDays;
        this.occurrences = 2;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.nextExpected = lastSeen.plusDays(intervalDays);
        this.lastTransactionId = lastTransactionId;
        this.status = ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Matches one more charge to this series and moves the expected date on by one interval. */
    public void recordOccurrence(LocalDate date, UUID transactionId, Instant now) {
        this.occurrences++;
        this.lastSeen = date;
        this.nextExpected = date.plusDays(intervalDays);
        this.lastTransactionId = transactionId;
        this.updatedAt = now;
    }

    /** Re-bases the established price after a change the detector has dealt with. */
    public void repriceTo(BigDecimal amount, Instant now) {
        this.typicalAmount = amount;
        this.updatedAt = now;
    }

    /** Closes the series after a sweep reported the expected charge missing. */
    public void lapse(Instant now) {
        this.status = LAPSED;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTypicalAmount() {
        return typicalAmount;
    }

    public int getIntervalDays() {
        return intervalDays;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public LocalDate getFirstSeen() {
        return firstSeen;
    }

    public LocalDate getLastSeen() {
        return lastSeen;
    }

    public LocalDate getNextExpected() {
        return nextExpected;
    }

    public UUID getLastTransactionId() {
        return lastTransactionId;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
