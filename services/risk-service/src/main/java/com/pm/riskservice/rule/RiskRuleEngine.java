package com.pm.riskservice.rule;

import com.pm.riskservice.entity.ExpenseObservation;
import com.pm.riskservice.event.EventTimes;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates all risk rules for a consumed {@code TransactionCreated} and returns the ones
 * that fired. All three rules are EXPENSE-only.
 *
 * <p>The current EXPENSE is recorded first (idempotently, keyed by the source event id),
 * then the windowed rules are evaluated with SQL count/sum that include it. The save and
 * the queries run in one transaction so the just-recorded row is visible to the aggregates.
 *
 * <h4>Trigger semantics — fire on crossing, not on every event past the threshold</h4>
 * <ul>
 *   <li><b>HIGH_AMOUNT_EXPENSE</b>: this event's amount &ge; {@link #HIGH_AMOUNT_THRESHOLD}.</li>
 *   <li><b>RAPID_SPENDING</b>: fires when this event is the {@link #RAPID_COUNT}-th expense
 *       within {@link #RAPID_WINDOW} (count == threshold), so a burst alerts once rather
 *       than on every subsequent expense.</li>
 *   <li><b>LARGE_DAILY_SPEND</b>: fires when this event pushes the day's total from at-or-below
 *       to above {@link #DAILY_THRESHOLD} (a single crossing per day).</li>
 * </ul>
 *
 * <p>Idempotency keeps the aggregates correct under at-least-once redelivery; the tradeoff
 * is that if the process dies after recording but before the consumer publishes, that one
 * detection can be lost (acceptable for the MVP).
 */
@Service
public class RiskRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleEngine.class);

    private static final String EXPENSE = "EXPENSE";
    static final BigDecimal HIGH_AMOUNT_THRESHOLD = new BigDecimal("10000000");
    static final int RAPID_COUNT = 5;
    static final Duration RAPID_WINDOW = Duration.ofMinutes(10);
    static final BigDecimal DAILY_THRESHOLD = new BigDecimal("20000000");

    private final ObservedExpenseRepository repository;

    public RiskRuleEngine(ObservedExpenseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<RiskRule> evaluate(TransactionCreatedEvent event) {
        if (!EXPENSE.equals(event.type()) || event.amount() == null) {
            return List.of();
        }
        Instant occurredAt = EventTimes.parseInstant(event.occurredAt());
        LocalDate transactionDate = EventTimes.parseDate(event.transactionDate());
        if (occurredAt == null || transactionDate == null) {
            return List.of();
        }

        // Idempotency: a redelivered event must not be counted twice (it would inflate the
        // windowed aggregates). Keyed by the source event id; fall back to a fresh id only
        // when the producer omitted one (then dedup is not possible — rare).
        UUID id = event.eventId();
        if (id != null) {
            if (repository.existsById(id)) {
                return List.of();
            }
        } else {
            id = UUID.randomUUID();
        }
        repository.save(new ExpenseObservation(
                id, event.userId(), event.categoryId(), event.amount(), event.currency(),
                occurredAt, transactionDate));

        List<RiskRule> fired = new ArrayList<>();

        if (event.amount().compareTo(HIGH_AMOUNT_THRESHOLD) >= 0) {
            fired.add(RiskRule.HIGH_AMOUNT_EXPENSE);
        }

        long windowCount = repository.countByUserIdAndOccurredAtBetween(
                event.userId(), occurredAt.minus(RAPID_WINDOW), occurredAt);
        if (windowCount == RAPID_COUNT) {
            fired.add(RiskRule.RAPID_SPENDING);
        }

        BigDecimal dayTotal = repository.sumAmountForDay(event.userId(), transactionDate);
        BigDecimal dayTotalBefore = dayTotal.subtract(event.amount());
        if (dayTotalBefore.compareTo(DAILY_THRESHOLD) <= 0
                && dayTotal.compareTo(DAILY_THRESHOLD) > 0) {
            fired.add(RiskRule.LARGE_DAILY_SPEND);
        }

        if (!fired.isEmpty()) {
            log.debug("Rules fired for transactionId={} userId={}: {}",
                    event.transactionId(), event.userId(), fired);
        }
        return fired;
    }
}
