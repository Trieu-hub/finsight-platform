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
 * that fired. There are two symmetric families: EXPENSE rules (money leaving) and INCOME
 * rules (money arriving — an expense tracker should be just as suspicious of unexplained
 * money in as of money out).
 *
 * <p>The observation is recorded first (idempotently, keyed by the source event id), then the
 * windowed rules are evaluated with SQL count/sum that include it. The save and the queries
 * run in one transaction so the just-recorded row is visible to the aggregates.
 *
 * <h4>Trigger semantics — fire on crossing, not on every event past the threshold</h4>
 * <ul>
 *   <li><b>HIGH_AMOUNT_EXPENSE</b> / <b>HIGH_AMOUNT_INCOME</b>: this event's amount &ge; the
 *       absolute threshold.</li>
 *   <li><b>RAPID_SPENDING</b> / <b>RAPID_INCOME</b>: fires when this event is the
 *       {@link #RAPID_COUNT}-th of its type within {@link #RAPID_WINDOW} (count == threshold),
 *       so a burst alerts once rather than on every subsequent event.</li>
 *   <li><b>LARGE_DAILY_SPEND</b> / <b>LARGE_DAILY_INCOME</b>: fires when this event pushes the
 *       day's total from at-or-below to above the threshold (a single crossing per day).</li>
 *   <li><b>INCOME_SPIKE</b>: this income is at least {@link #SPIKE_FACTOR}× the user's own mean
 *       income, once {@link #SPIKE_MIN_HISTORY} prior incomes exist. Relative to the user, so it
 *       catches a suspicious jump at any absolute scale.</li>
 * </ul>
 *
 * <p>INCOME rows are shared with the insights (LOW_SAVINGS_RATE); {@code InsightService} records
 * them too, but both writes are idempotent on the event id, so whichever runs first wins and the
 * other is a no-op.
 *
 * <p>Idempotency keeps the aggregates correct under at-least-once redelivery; the tradeoff
 * is that if the process dies after recording but before the consumer publishes, that one
 * detection can be lost (acceptable for the MVP).
 */
@Service
public class RiskRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleEngine.class);

    private static final String EXPENSE = "EXPENSE";
    private static final String INCOME = "INCOME";

    static final BigDecimal HIGH_AMOUNT_THRESHOLD = new BigDecimal("10000000");
    static final int RAPID_COUNT = 5;
    static final Duration RAPID_WINDOW = Duration.ofMinutes(10);
    static final BigDecimal DAILY_THRESHOLD = new BigDecimal("20000000");

    // Income thresholds sit higher than the expense ones: a salary is legitimately large, so
    // alerting at the expense threshold would fire on every payday.
    static final BigDecimal HIGH_INCOME_THRESHOLD = new BigDecimal("50000000");
    static final BigDecimal DAILY_INCOME_THRESHOLD = new BigDecimal("100000000");
    static final BigDecimal SPIKE_FACTOR = new BigDecimal("3");
    static final long SPIKE_MIN_HISTORY = 10;

    private final ObservedExpenseRepository repository;

    public RiskRuleEngine(ObservedExpenseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<RiskRule> evaluate(TransactionCreatedEvent event) {
        boolean expense = EXPENSE.equals(event.type());
        boolean income = INCOME.equals(event.type());
        if ((!expense && !income) || event.amount() == null) {
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
                id, event.userId(), event.type(), event.categoryId(), event.amount(),
                event.currency(), occurredAt, transactionDate));

        List<RiskRule> fired = expense
                ? evaluateExpense(event, occurredAt, transactionDate)
                : evaluateIncome(event, occurredAt, transactionDate);

        if (!fired.isEmpty()) {
            log.debug("Rules fired for transactionId={} userId={}: {}",
                    event.transactionId(), event.userId(), fired);
        }
        return fired;
    }

    private List<RiskRule> evaluateExpense(TransactionCreatedEvent event, Instant occurredAt,
                                           LocalDate transactionDate) {
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
        if (crosses(dayTotal, event.amount(), DAILY_THRESHOLD)) {
            fired.add(RiskRule.LARGE_DAILY_SPEND);
        }
        return fired;
    }

    private List<RiskRule> evaluateIncome(TransactionCreatedEvent event, Instant occurredAt,
                                          LocalDate transactionDate) {
        List<RiskRule> fired = new ArrayList<>();

        if (event.amount().compareTo(HIGH_INCOME_THRESHOLD) >= 0) {
            fired.add(RiskRule.HIGH_AMOUNT_INCOME);
        }

        long windowCount = repository.countIncomeByUserIdAndOccurredAtBetween(
                event.userId(), occurredAt.minus(RAPID_WINDOW), occurredAt);
        if (windowCount == RAPID_COUNT) {
            fired.add(RiskRule.RAPID_INCOME);
        }

        BigDecimal dayTotal = repository.sumIncomeForDay(event.userId(), transactionDate);
        if (crosses(dayTotal, event.amount(), DAILY_INCOME_THRESHOLD)) {
            fired.add(RiskRule.LARGE_DAILY_INCOME);
        }

        // Relative to the user's own history — needs enough history to have a meaningful mean.
        var baseline = repository.incomeBaselineBefore(event.userId(), occurredAt);
        if (baseline != null && baseline.getCount() >= SPIKE_MIN_HISTORY
                && baseline.getAverage() != null
                && baseline.getAverage().signum() > 0
                && event.amount().compareTo(baseline.getAverage().multiply(SPIKE_FACTOR)) >= 0) {
            fired.add(RiskRule.INCOME_SPIKE);
        }
        return fired;
    }

    /** True when adding {@code amount} pushed {@code total} from at-or-below to above {@code threshold}. */
    private static boolean crosses(BigDecimal total, BigDecimal amount, BigDecimal threshold) {
        BigDecimal before = total.subtract(amount);
        return before.compareTo(threshold) <= 0 && total.compareTo(threshold) > 0;
    }
}
