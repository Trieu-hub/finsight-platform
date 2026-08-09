package com.pm.riskservice.rule;

import com.pm.riskservice.entity.ExpenseObservation;
import com.pm.riskservice.event.EventTimes;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.recurring.RecurringDetector;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <h4>The bar is the person, not a number</h4>
 * <p>The four monetary rules draw their threshold from the user's own history rather than from a
 * platform-wide constant: {@link #ALERT_MULTIPLE}× their mean, floored at a tenth of the flat
 * threshold, and falling back to the flat threshold itself until {@link #BASELINE_MIN_HISTORY}
 * observations exist. A fixed 10,000,000 is noise to someone who spends it weekly and silence to
 * someone whose largest ever expense is a tenth of that; neither is served by the same number.
 * The count-based rules stay absolute — five transactions in ten minutes is a shape of behaviour,
 * not an amount, and it does not scale with wealth.
 *
 * <h4>Trigger semantics — fire on crossing, not on every event past the threshold</h4>
 * <ul>
 *   <li><b>HIGH_AMOUNT_EXPENSE</b> / <b>HIGH_AMOUNT_INCOME</b>: this event's amount &ge; the
 *       user's own bar (see above).</li>
 *   <li><b>RAPID_SPENDING</b> / <b>RAPID_INCOME</b>: fires when this event is the
 *       {@link #RAPID_COUNT}-th of its type within {@link #RAPID_WINDOW} (count == threshold),
 *       so a burst alerts once rather than on every subsequent event.</li>
 *   <li><b>LARGE_DAILY_SPEND</b> / <b>LARGE_DAILY_INCOME</b>: fires when this event pushes the
 *       day's total from at-or-below to above the user's own daily bar — the mean of their prior
 *       daily totals, on days they actually transacted (a single crossing per day).</li>
 *   <li><b>INCOME_SPIKE</b>: this income is at least {@link #SPIKE_FACTOR}× the user's own mean
 *       income, once {@link #SPIKE_MIN_HISTORY} prior incomes exist. Unchanged — it was already
 *       relative, and is the pattern the four rules above were brought in line with.</li>
 *   <li><b>RECURRING_CHARGE_DETECTED</b> / <b>RECURRING_PRICE_INCREASE</b>: delegated to
 *       {@link RecurringDetector}, which matches the expense against the user's recurring
 *       series. (Its third rule, RECURRING_CHARGE_MISSED, has no triggering event and is
 *       raised by the scheduled sweep instead.)</li>
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

    // Flat thresholds. These are no longer the bar for an established user — they are the
    // cold-start fallback, and a tenth of each is the floor the relative bar cannot sink below.
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

    /**
     * How many times their own mean a user has to spend (or receive) before it is worth alerting
     * on. Deliberately above the anomaly detector's 3×: the two are tiers of the same idea, and
     * they would be the same rule at the same multiple. 3× is "worth recording", 5× is "worth
     * interrupting someone over".
     */
    static final BigDecimal ALERT_MULTIPLE = new BigDecimal("5");

    /**
     * Prior observations needed before the mean is trusted. Matches the anomaly detector's
     * {@code MIN_HISTORY} and {@link #SPIKE_MIN_HISTORY} so all three agree on what "enough
     * history" means.
     */
    static final long BASELINE_MIN_HISTORY = 10;

    /**
     * The relative bar is divided down from the flat threshold to get its floor, rather than each
     * rule carrying its own constant. A tenth keeps the expense/income ratio the flat thresholds
     * already encode (1M, 2M, 5M, 10M) and stops a user whose whole financial life is small
     * amounts from getting a HIGH severity alert over a cup of coffee.
     */
    private static final BigDecimal FLOOR_DIVISOR = BigDecimal.TEN;

    private final ObservedExpenseRepository repository;
    private final RecurringDetector recurringDetector;

    public RiskRuleEngine(ObservedExpenseRepository repository, RecurringDetector recurringDetector) {
        this.repository = repository;
        this.recurringDetector = recurringDetector;
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

        BigDecimal amountBar = barFrom(
                repository.expenseBaselineBefore(event.userId(), occurredAt), HIGH_AMOUNT_THRESHOLD);
        if (event.amount().compareTo(amountBar) >= 0) {
            fired.add(RiskRule.HIGH_AMOUNT_EXPENSE);
        }

        long windowCount = repository.countByUserIdAndOccurredAtBetween(
                event.userId(), occurredAt.minus(RAPID_WINDOW), occurredAt);
        if (windowCount == RAPID_COUNT) {
            fired.add(RiskRule.RAPID_SPENDING);
        }

        BigDecimal dailyBar = dailyBarFrom(
                repository.dailyExpenseBaselineBefore(event.userId(), transactionDate), DAILY_THRESHOLD);
        BigDecimal dayTotal = repository.sumAmountForDay(event.userId(), transactionDate);
        if (crosses(dayTotal, event.amount(), dailyBar)) {
            fired.add(RiskRule.LARGE_DAILY_SPEND);
        }

        // Recurring charges (Phase G.1). Evaluated here rather than beside the insights so it
        // inherits this method's idempotency: a redelivered event returns above, before any of
        // this runs, and cannot count the same charge into a series twice.
        fired.addAll(recurringDetector.evaluate(event.userId(), event.categoryId(),
                event.currency(), event.amount(), event.transactionId(), transactionDate));
        return fired;
    }

    private List<RiskRule> evaluateIncome(TransactionCreatedEvent event, Instant occurredAt,
                                          LocalDate transactionDate) {
        List<RiskRule> fired = new ArrayList<>();

        var baseline = repository.incomeBaselineBefore(event.userId(), occurredAt);

        if (event.amount().compareTo(barFrom(baseline, HIGH_INCOME_THRESHOLD)) >= 0) {
            fired.add(RiskRule.HIGH_AMOUNT_INCOME);
        }

        long windowCount = repository.countIncomeByUserIdAndOccurredAtBetween(
                event.userId(), occurredAt.minus(RAPID_WINDOW), occurredAt);
        if (windowCount == RAPID_COUNT) {
            fired.add(RiskRule.RAPID_INCOME);
        }

        BigDecimal dailyBar = dailyBarFrom(
                repository.dailyIncomeBaselineBefore(event.userId(), transactionDate),
                DAILY_INCOME_THRESHOLD);
        BigDecimal dayTotal = repository.sumIncomeForDay(event.userId(), transactionDate);
        if (crosses(dayTotal, event.amount(), dailyBar)) {
            fired.add(RiskRule.LARGE_DAILY_INCOME);
        }

        // Relative to the user's own history — needs enough history to have a meaningful mean.
        if (baseline != null && baseline.getCount() >= SPIKE_MIN_HISTORY
                && baseline.getAverage() != null
                && baseline.getAverage().signum() > 0
                && event.amount().compareTo(baseline.getAverage().multiply(SPIKE_FACTOR)) >= 0) {
            fired.add(RiskRule.INCOME_SPIKE);
        }
        return fired;
    }

    /**
     * The bar a single transaction is judged against: {@link #ALERT_MULTIPLE} × this user's own
     * mean, never below a tenth of {@code flat}, and {@code flat} itself until they have enough
     * history for a mean to mean anything.
     *
     * <p>This is the whole point of the rule being per-person. A flat 10,000,000 is noise to
     * someone who spends that weekly and silence to someone whose largest ever expense is a
     * tenth of it; both get a bar drawn where their own behaviour actually is.
     */
    private static BigDecimal barFrom(ObservedExpenseRepository.ExpenseBaseline baseline,
                                      BigDecimal flat) {
        if (baseline == null || baseline.getCount() < BASELINE_MIN_HISTORY
                || baseline.getAverage() == null || baseline.getAverage().signum() <= 0) {
            return flat;
        }
        return baseline.getAverage().multiply(ALERT_MULTIPLE).max(floorOf(flat));
    }

    /**
     * The same idea for a whole day, against the mean of this user's previous daily totals.
     * {@code total / days} is that mean: the query counts only days they actually transacted on,
     * so quiet days do not drag the bar down and make an ordinary day look alarming.
     */
    private static BigDecimal dailyBarFrom(ObservedExpenseRepository.DailyBaseline baseline,
                                           BigDecimal flat) {
        if (baseline == null || baseline.getDays() < BASELINE_MIN_HISTORY
                || baseline.getTotal() == null || baseline.getTotal().signum() <= 0) {
            return flat;
        }
        BigDecimal meanDay = baseline.getTotal()
                .divide(BigDecimal.valueOf(baseline.getDays()), 2, RoundingMode.HALF_UP);
        return meanDay.multiply(ALERT_MULTIPLE).max(floorOf(flat));
    }

    private static BigDecimal floorOf(BigDecimal flat) {
        return flat.divide(FLOOR_DIVISOR, 2, RoundingMode.HALF_UP);
    }

    /** True when adding {@code amount} pushed {@code total} from at-or-below to above {@code threshold}. */
    private static boolean crosses(BigDecimal total, BigDecimal amount, BigDecimal threshold) {
        BigDecimal before = total.subtract(amount);
        return before.compareTo(threshold) <= 0 && total.compareTo(threshold) > 0;
    }
}
