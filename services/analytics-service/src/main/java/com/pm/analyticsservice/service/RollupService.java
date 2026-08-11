package com.pm.analyticsservice.service;

import com.pm.analyticsservice.catalog.CategoryCatalog;
import com.pm.analyticsservice.entity.DailyCategoryRollup;
import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.entity.ProcessedEvent;
import com.pm.analyticsservice.event.TransactionCreatedEvent;
import com.pm.analyticsservice.repository.DailyCategoryRollupRepository;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Folds each {@code TransactionCreated} event into the monthly rollup read model.
 *
 * <p>Idempotent: the {@code processed_events} inbox short-circuits a redelivered event
 * before any counting, and the inbox row is written in the SAME transaction as the
 * rollup upsert — so a crash mid-apply rolls both back and the redelivery retries
 * cleanly. There is no external call here, so the whole apply is a single short
 * transaction (unlike notification-service, whose narration runs outside the tx).
 *
 * <p>The listener container runs single-threaded (default concurrency 1), so the
 * read-modify-write on a rollup row is not contended across consumer threads.
 */
@Service
public class RollupService {

    private final MonthlyCategoryRollupRepository rollupRepository;
    private final DailyCategoryRollupRepository dailyRollupRepository;
    private final ProcessedEventRepository processedEventRepository;

    public RollupService(MonthlyCategoryRollupRepository rollupRepository,
                         DailyCategoryRollupRepository dailyRollupRepository,
                         ProcessedEventRepository processedEventRepository) {
        this.rollupRepository = rollupRepository;
        this.dailyRollupRepository = dailyRollupRepository;
        this.processedEventRepository = processedEventRepository;
    }

    /** @return true if the event was applied, false if it was a duplicate (already in the inbox). */
    @Transactional
    public boolean apply(TransactionCreatedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        String yearMonth = yearMonthOf(event);
        long categoryId = event.categoryId() == null ? CategoryCatalog.UNCATEGORIZED : event.categoryId();
        String currency = event.currency() == null ? "USD" : event.currency();
        BigDecimal amount = event.amount() == null ? BigDecimal.ZERO : event.amount();

        MonthlyCategoryRollup rollup = rollupRepository
                .findByUserIdAndYearMonthAndCategoryIdAndTypeAndCurrency(
                        event.userId(), yearMonth, categoryId, event.type(), currency)
                .orElseGet(() -> MonthlyCategoryRollup.builder()
                        .id(UUID.randomUUID())
                        .userId(event.userId())
                        .yearMonth(yearMonth)
                        .categoryId(categoryId)
                        .type(event.type())
                        .currency(currency)
                        .totalAmount(BigDecimal.ZERO)
                        .txnCount(0)
                        .updatedAt(now)
                        .build());

        rollup.setTotalAmount(rollup.getTotalAmount().add(amount));
        rollup.setTxnCount(rollup.getTxnCount() + 1);
        rollup.setUpdatedAt(now);
        rollupRepository.save(rollup);

        applyDaily(event, categoryId, currency, amount, now);

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .processedAt(now)
                .build());
        return true;
    }

    /**
     * The same fold at day granularity, in this same transaction and behind the same inbox
     * check — so the daily series can never drift from the monthly one.
     *
     * <p>It is a second write per event rather than a view over the monthly rows because a
     * month total cannot be taken apart into days after the fact, and the weekly pattern the
     * forecast model learns lives entirely in that detail.
     */
    private void applyDaily(TransactionCreatedEvent event, long categoryId, String currency,
                            BigDecimal amount, LocalDateTime now) {
        LocalDate spendDate = spendDateOf(event);

        DailyCategoryRollup daily = dailyRollupRepository
                .findByUserIdAndSpendDateAndCategoryIdAndTypeAndCurrency(
                        event.userId(), spendDate, categoryId, event.type(), currency)
                .orElseGet(() -> DailyCategoryRollup.builder()
                        .id(UUID.randomUUID())
                        .userId(event.userId())
                        .spendDate(spendDate)
                        .categoryId(categoryId)
                        .type(event.type())
                        .currency(currency)
                        .totalAmount(BigDecimal.ZERO)
                        .txnCount(0)
                        .updatedAt(now)
                        .build());

        daily.setTotalAmount(daily.getTotalAmount().add(amount));
        daily.setTxnCount(daily.getTxnCount() + 1);
        daily.setUpdatedAt(now);
        dailyRollupRepository.save(daily);
    }

    /**
     * The month the transaction belongs to: the business {@code transactionDate}
     * ({@code YYYY-MM-DD}) when present, else the envelope {@code occurredAt} (ISO-8601).
     * Both begin with {@code YYYY-MM}, so the first 7 characters are the month key.
     */
    private String yearMonthOf(TransactionCreatedEvent event) {
        return dateBasisOf(event).substring(0, 7);
    }

    /** Same basis as {@link #yearMonthOf}, taken to the day: the first 10 characters. */
    private LocalDate spendDateOf(TransactionCreatedEvent event) {
        return LocalDate.parse(dateBasisOf(event).substring(0, 10));
    }

    private String dateBasisOf(TransactionCreatedEvent event) {
        return event.transactionDate() != null ? event.transactionDate() : event.occurredAt();
    }
}
