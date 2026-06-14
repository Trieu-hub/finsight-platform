package com.pm.riskservice.service;

import com.pm.riskservice.entity.BudgetSnapshot;
import com.pm.riskservice.entity.ExpenseObservation;
import com.pm.riskservice.entity.Insight;
import com.pm.riskservice.event.EventTimes;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.insight.InsightType;
import com.pm.riskservice.repository.BudgetSnapshotRepository;
import com.pm.riskservice.repository.InsightRepository;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates the behavioral insights from the {@code observed_expenses} data already recorded
 * for the risk rules, plus a local budget read-model — no new ingestion, no ML, no prediction,
 * no anomaly detection. Evaluated on each consumed EXPENSE.
 *
 * <ul>
 *   <li><b>SPENDING_INCREASE</b> (E.1): current month total &ge; previous month +30%.</li>
 *   <li><b>CATEGORY_SURGE</b> (E.2): current month total in the event's category &ge; previous
 *       month in that category +50%.</li>
 *   <li><b>BUDGET_RISK</b> (E.2): for a budget matching the event, spend over the budget window
 *       exceeds 80% of its limit while the period is still open.</li>
 *   <li><b>LOW_SAVINGS_RATE</b> (E.3): for a month with positive income, expenses reach at least
 *       80% of that income.</li>
 * </ul>
 *
 * <p>To support LOW_SAVINGS_RATE this service also records consumed INCOME transactions into
 * {@code observed_expenses} (the risk engine records EXPENSE), keyed by the source event id for
 * idempotency — the income side of the same read-model, no new store.
 *
 * <h4>Idempotency &amp; "fire once"</h4>
 * Each insight is keyed by (user, type, period_month, subject) — subject being the category id,
 * the budget id, or {@code '-'}. The first qualifying event flags it; later events for the same
 * subject/period are no-ops (backed by the V5 unique constraint). A previous-period baseline is
 * required for the month-over-month rules; "no spend → some spend" is not a meaningful increase.
 *
 * <p>Spend totals come only from expenses risk-service has itself observed (the read-model is
 * eventually consistent and may lag, exactly as budget-service's own {@code spent_amount} does).
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    static final String GENERATED_COUNTER = "finsight.insights.generated";
    /** SPENDING_INCREASE subject (a single user-level insight per month). */
    static final String USER_SUBJECT = "-";

    private static final String EXPENSE = "EXPENSE";
    private static final String INCOME = "INCOME";
    /** Current month must be at least 1.30× the previous month (a +30% increase). */
    static final BigDecimal SPENDING_INCREASE_THRESHOLD = new BigDecimal("1.30");
    /** Current month in a category must be at least 1.50× the previous (a +50% increase). */
    static final BigDecimal CATEGORY_SURGE_THRESHOLD = new BigDecimal("1.50");
    /** Budget utilization must exceed 80%. */
    static final BigDecimal BUDGET_UTILIZATION_THRESHOLD = new BigDecimal("80");
    /** LOW_SAVINGS_RATE fires when current-month expenses reach at least 80% of income. */
    static final BigDecimal LOW_SAVINGS_EXPENSE_RATIO = new BigDecimal("0.80");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ObservedExpenseRepository expenseRepository;
    private final InsightRepository insightRepository;
    private final BudgetSnapshotRepository budgetRepository;
    private final Map<InsightType, Counter> counters = new EnumMap<>(InsightType.class);

    public InsightService(ObservedExpenseRepository expenseRepository,
                          InsightRepository insightRepository,
                          BudgetSnapshotRepository budgetRepository,
                          MeterRegistry meterRegistry) {
        this.expenseRepository = expenseRepository;
        this.insightRepository = insightRepository;
        this.budgetRepository = budgetRepository;
        // Register all type-tagged series eagerly so each is exported at 0 from startup.
        for (InsightType type : InsightType.values()) {
            counters.put(type, Counter.builder(GENERATED_COUNTER)
                    .description("Behavioral insights generated")
                    .tag("type", type.name())
                    .register(meterRegistry));
        }
    }

    /**
     * Evaluates all insight rules for {@code event} and returns those generated. The observed
     * expense for this event is assumed already recorded (by the risk engine) before this runs.
     */
    @Transactional
    public List<Insight> evaluate(TransactionCreatedEvent event) {
        List<Insight> generated = new ArrayList<>();
        LocalDate transactionDate = EventTimes.parseDate(event.transactionDate());
        if (transactionDate == null) {
            return generated;
        }
        // INCOME feeds LOW_SAVINGS_RATE's income side; record it but produce no insight directly.
        if (INCOME.equals(event.type())) {
            recordIncome(event, transactionDate);
            return generated;
        }
        if (!EXPENSE.equals(event.type())) {
            return generated;
        }

        evaluateSpendingIncrease(event.userId(), transactionDate).ifPresent(generated::add);
        evaluateCategorySurge(event.userId(), event.categoryId(), transactionDate)
                .ifPresent(generated::add);
        generated.addAll(evaluateBudgetRisk(
                event.userId(), event.categoryId(), event.currency(), transactionDate));
        evaluateLowSavingsRate(event.userId(), transactionDate).ifPresent(generated::add);
        return generated;
    }

    /** All insights, newest first (backs the read API). */
    @Transactional(readOnly = true)
    public List<Insight> findAll() {
        return insightRepository.findAllByOrderByGeneratedAtDesc();
    }

    // --- SPENDING_INCREASE (user-level, month over month) -------------------------------------

    private Optional<Insight> evaluateSpendingIncrease(Long userId, LocalDate date) {
        YearMonth current = YearMonth.from(date);
        BigDecimal currentTotal = monthTotal(userId, current);
        BigDecimal previousTotal = monthTotal(userId, current.minusMonths(1));
        if (!exceedsBy(currentTotal, previousTotal, SPENDING_INCREASE_THRESHOLD)) {
            return Optional.empty();
        }
        return persist(InsightType.SPENDING_INCREASE, userId, current.toString(), null,
                USER_SUBJECT, previousTotal, currentTotal, increasePct(previousTotal, currentTotal));
    }

    // --- CATEGORY_SURGE (per-category, month over month) --------------------------------------

    private Optional<Insight> evaluateCategorySurge(Long userId, Long categoryId,
                                                              LocalDate date) {
        if (categoryId == null) {
            return Optional.empty();
        }
        YearMonth current = YearMonth.from(date);
        BigDecimal currentTotal = categoryMonthTotal(userId, categoryId, current);
        BigDecimal previousTotal = categoryMonthTotal(userId, categoryId, current.minusMonths(1));
        if (!exceedsBy(currentTotal, previousTotal, CATEGORY_SURGE_THRESHOLD)) {
            return Optional.empty();
        }
        return persist(InsightType.CATEGORY_SURGE, userId, current.toString(), categoryId,
                String.valueOf(categoryId), previousTotal, currentTotal,
                increasePct(previousTotal, currentTotal));
    }

    // --- LOW_SAVINGS_RATE (user-level, current-month expenses vs income) ----------------------

    private Optional<Insight> evaluateLowSavingsRate(Long userId, LocalDate date) {
        YearMonth current = YearMonth.from(date);
        BigDecimal income = monthIncome(userId, current);
        if (income.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal expenses = monthTotal(userId, current);
        if (expenses.compareTo(income.multiply(LOW_SAVINGS_EXPENSE_RATIO)) < 0) {
            return Optional.empty();
        }
        // pct is the share of income consumed by expenses, e.g. 85.00 for "spent 85% of income".
        BigDecimal spendPct = expenses.multiply(HUNDRED).divide(income, 2, RoundingMode.HALF_UP);
        return persist(InsightType.LOW_SAVINGS_RATE, userId, current.toString(), null,
                USER_SUBJECT, income, expenses, spendPct);
    }

    /** Records a consumed INCOME transaction into observed_expenses (idempotent by event id). */
    private void recordIncome(TransactionCreatedEvent event, LocalDate transactionDate) {
        if (event.amount() == null) {
            return;
        }
        Instant occurredAt = EventTimes.parseInstant(event.occurredAt());
        if (occurredAt == null) {
            return;
        }
        UUID id = event.eventId();
        if (id != null) {
            if (expenseRepository.existsById(id)) {
                return;
            }
        } else {
            id = UUID.randomUUID();
        }
        expenseRepository.save(new ExpenseObservation(id, event.userId(), ExpenseObservation.INCOME,
                event.categoryId(), event.amount(), event.currency(), occurredAt, transactionDate));
    }

    // --- BUDGET_RISK (per matching budget, utilization > 80% within the period) ---------------

    private List<Insight> evaluateBudgetRisk(Long userId, Long categoryId, String currency,
                                             LocalDate date) {
        List<Insight> fired = new ArrayList<>();
        if (categoryId == null || currency == null) {
            return fired;
        }
        // Matching a budget whose window contains the transaction date means the period is still
        // open at the moment of the transaction — i.e. "before the budget period ends".
        List<BudgetSnapshot> budgets =
                budgetRepository.findActiveMatching(userId, categoryId, currency, date);
        for (BudgetSnapshot budget : budgets) {
            BigDecimal limit = nz(budget.getLimitAmount());
            if (limit.signum() <= 0) {
                continue;
            }
            BigDecimal spent = nz(expenseRepository.sumAmountForBudgetWindow(
                    userId, categoryId, currency, budget.getStartDate(), budget.getEndDate()));
            BigDecimal utilization = spent.multiply(HUNDRED).divide(limit, 2, RoundingMode.HALF_UP);
            if (utilization.compareTo(BUDGET_UTILIZATION_THRESHOLD) <= 0) {
                continue;
            }
            // Dedup per budget; period_month is the budget's start month (stable for the period).
            String periodMonth = YearMonth.from(budget.getStartDate()).toString();
            persist(InsightType.BUDGET_RISK, userId, periodMonth, categoryId,
                    budget.getId().toString(), limit, spent, utilization).ifPresent(fired::add);
        }
        return fired;
    }

    // --- shared persistence -------------------------------------------------------------------

    private Optional<Insight> persist(InsightType type, Long userId, String periodMonth,
                                                Long categoryId, String subjectId,
                                                BigDecimal previous, BigDecimal current,
                                                BigDecimal pct) {
        if (insightRepository.existsByUserIdAndInsightTypeAndPeriodMonthAndSubjectId(
                userId, type.name(), periodMonth, subjectId)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Insight insight = insightRepository.save(new Insight(
                UUID.randomUUID(), userId, type.name(), periodMonth, categoryId, subjectId,
                previous, current, pct, now, now));
        counters.get(type).increment();
        log.info("Insight generated [{}]: userId={}, period={}, categoryId={}, previous={}, current={}, pct={}",
                type, userId, periodMonth, categoryId, previous, current, pct);
        return Optional.of(insight);
    }

    private BigDecimal monthTotal(Long userId, YearMonth month) {
        return nz(expenseRepository.sumAmountInDateRange(
                userId, month.atDay(1), month.plusMonths(1).atDay(1)));
    }

    private BigDecimal categoryMonthTotal(Long userId, Long categoryId, YearMonth month) {
        return nz(expenseRepository.sumAmountForCategoryInDateRange(
                userId, categoryId, month.atDay(1), month.plusMonths(1).atDay(1)));
    }

    private BigDecimal monthIncome(Long userId, YearMonth month) {
        return nz(expenseRepository.sumIncomeInDateRange(
                userId, month.atDay(1), month.plusMonths(1).atDay(1)));
    }

    /** True when {@code current >= previous * threshold} and there is a positive baseline. */
    private static boolean exceedsBy(BigDecimal current, BigDecimal previous, BigDecimal threshold) {
        if (previous.signum() <= 0) {
            return false;
        }
        return current.compareTo(previous.multiply(threshold)) >= 0;
    }

    private static BigDecimal increasePct(BigDecimal previous, BigDecimal current) {
        return current.subtract(previous).multiply(HUNDRED).divide(previous, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
