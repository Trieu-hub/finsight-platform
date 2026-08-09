package com.pm.riskservice.recurring;

import com.pm.riskservice.entity.RecurringSeries;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import com.pm.riskservice.repository.RecurringSeriesRepository;
import com.pm.riskservice.rule.RiskRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Recognises charges that keep coming back (Phase G.1) and reports what changes about them.
 * Called from the rule engine on each consumed EXPENSE, after the observation has been
 * recorded — so it inherits the engine's idempotency: a redelivered event never reaches here
 * and cannot count an occurrence twice.
 *
 * <p>Two of the three recurring signals come from this class; the third, a charge that failed
 * to arrive, is an absence and belongs to {@link RecurringSweeper}.
 *
 * <ul>
 *   <li><b>RECURRING_CHARGE_DETECTED</b> — the {@link #CONFIRM_OCCURRENCES}rd charge in a
 *       series. Two charges an interval apart could be coincidence; three is a pattern.</li>
 *   <li><b>RECURRING_PRICE_INCREASE</b> — an established series charged at least
 *       {@link #PRICE_INCREASE_FACTOR}× the price it had settled at.</li>
 * </ul>
 *
 * <h4>What a "subscription" can be here</h4>
 * <p>{@code TransactionCreated} carries no merchant and no description, so a series can only be
 * identified by (user, category, currency, roughly this amount) repeating on a cadence. Two
 * unrelated charges of similar size in one category will be read as one series. That is a
 * limitation of the event contract, not a modelling choice, and the cadence requirement is what
 * keeps it from firing on ordinary shopping.
 *
 * <h4>Three tolerances, deliberately different</h4>
 * <ul>
 *   <li>{@link #SEED_TOLERANCE} (10%) decides whether two charges are the same charge. It is
 *       tight because this is the weakest evidence in the whole feature.</li>
 *   <li>{@link #MATCH_TOLERANCE} (25%) decides whether a new charge belongs to a series that
 *       already exists. It has to be wider than the price-increase threshold, or a subscription
 *       that went up in price would stop matching its own series and quietly seed a second one
 *       instead of being reported.</li>
 *   <li>{@link #PRICE_INCREASE_FACTOR} (1.15) is what counts as a rise worth interrupting
 *       someone over, and it sits inside the match band for the reason above.</li>
 * </ul>
 *
 * <p>Everything is deterministic arithmetic over dates and amounts — no ML, no forecasting,
 * consistent with the rest of the intelligence layer.
 */
@Service
public class RecurringDetector {

    private static final Logger log = LoggerFactory.getLogger(RecurringDetector.class);

    /** The cadences recognised, in days: weekly, monthly, quarterly. */
    static final int[] CADENCE_DAYS = {7, 30, 91};
    /**
     * How far a gap may sit from a cadence and still count as that cadence, in days and in the
     * same order as {@link #CADENCE_DAYS}. Monthly is ±5 because calendar months are 28–31 days
     * long and a billing date lands on a weekend; the bands do not overlap.
     */
    static final int[] CADENCE_TOLERANCE_DAYS = {2, 5, 10};

    /** Charges must be within 10% of each other to open a series. */
    static final BigDecimal SEED_TOLERANCE = new BigDecimal("0.10");
    /** A charge within 25% of a series' established price belongs to that series. */
    static final BigDecimal MATCH_TOLERANCE = new BigDecimal("0.25");
    /** At or above 1.15× the established price is a reportable rise. */
    static final BigDecimal PRICE_INCREASE_FACTOR = new BigDecimal("1.15");
    /** At or below 0.85× re-bases the price silently — a charge getting cheaper is not a risk. */
    static final BigDecimal PRICE_DROP_FACTOR = new BigDecimal("0.85");
    /** Occurrences needed before a series is called recurring. */
    static final int CONFIRM_OCCURRENCES = 3;

    private static final PageRequest ONE_ROW = PageRequest.of(0, 1);

    private final RecurringSeriesRepository seriesRepository;
    private final ObservedExpenseRepository expenseRepository;

    public RecurringDetector(RecurringSeriesRepository seriesRepository,
                             ObservedExpenseRepository expenseRepository) {
        this.seriesRepository = seriesRepository;
        this.expenseRepository = expenseRepository;
    }

    /**
     * Matches one consumed EXPENSE against the user's recurring series and returns the rules
     * that fired. Runs inside the engine's transaction, so the series write commits with the
     * observation that produced it.
     */
    public List<RiskRule> evaluate(Long userId, Long categoryId, String currency,
                                   BigDecimal amount, UUID transactionId, LocalDate date) {
        if (categoryId == null || currency == null || amount == null || amount.signum() <= 0) {
            return List.of();
        }
        List<RecurringSeries> candidates = seriesRepository.findActiveMatches(
                userId, categoryId, currency, amount,
                BigDecimal.ONE.subtract(MATCH_TOLERANCE), BigDecimal.ONE.add(MATCH_TOLERANCE));

        for (RecurringSeries series : candidates) {
            if (fitsCadence(series, date)) {
                return continueSeries(series, amount, transactionId, date);
            }
        }
        // Only seed when nothing matched on price at all. A charge that matched a series on
        // price but arrived off-cadence is an ordinary purchase that happens to look like the
        // subscription — seeding on it would leave two series competing for the next one.
        if (candidates.isEmpty()) {
            seedSeries(userId, categoryId, currency, amount, transactionId, date);
        }
        return List.of();
    }

    /** Records the charge against an existing series and reports what changed. */
    private List<RiskRule> continueSeries(RecurringSeries series, BigDecimal amount,
                                          UUID transactionId, LocalDate date) {
        Instant now = Instant.now();
        BigDecimal established = series.getTypicalAmount();
        series.recordOccurrence(date, transactionId, now);

        List<RiskRule> fired = new ArrayList<>();
        if (series.getOccurrences() == CONFIRM_OCCURRENCES) {
            fired.add(RiskRule.RECURRING_CHARGE_DETECTED);
        } else if (series.getOccurrences() > CONFIRM_OCCURRENCES
                && amount.compareTo(established.multiply(PRICE_INCREASE_FACTOR)) >= 0) {
            // Re-base, so the next rise is measured from the new price rather than firing
            // again on every subsequent charge.
            series.repriceTo(amount, now);
            fired.add(RiskRule.RECURRING_PRICE_INCREASE);
        } else if (amount.compareTo(established.multiply(PRICE_DROP_FACTOR)) <= 0) {
            series.repriceTo(amount, now);
        }
        seriesRepository.save(series);

        if (!fired.isEmpty()) {
            log.info("Recurring series {} matched: userId={}, occurrences={}, established={}, amount={}, fired={}",
                    series.getId(), series.getUserId(), series.getOccurrences(), established, amount, fired);
        }
        return fired;
    }

    /**
     * Opens a series when this charge and one earlier charge of a similar amount are one
     * cadence apart. No rule fires — the series is a hypothesis until a third charge confirms it.
     */
    private void seedSeries(Long userId, Long categoryId, String currency, BigDecimal amount,
                            UUID transactionId, LocalDate date) {
        List<LocalDate> previous = expenseRepository.findSimilarExpenseDatesBefore(
                userId, categoryId, currency, date,
                lowerBound(amount, SEED_TOLERANCE), upperBound(amount, SEED_TOLERANCE), ONE_ROW);
        if (previous.isEmpty()) {
            return;
        }
        LocalDate priorDate = previous.get(0);
        Integer cadence = cadenceFor(ChronoUnit.DAYS.between(priorDate, date));
        if (cadence == null) {
            return;
        }
        RecurringSeries series = seriesRepository.save(new RecurringSeries(
                UUID.randomUUID(), userId, categoryId, currency, amount, cadence,
                priorDate, date, transactionId, Instant.now()));
        log.debug("Opened recurring series {}: userId={}, categoryId={}, every {} days, amount={}",
                series.getId(), userId, categoryId, cadence, amount);
    }

    /** True when this charge lands one of the series' intervals after the previous one. */
    static boolean fitsCadence(RecurringSeries series, LocalDate date) {
        long gap = ChronoUnit.DAYS.between(series.getLastSeen(), date);
        Integer cadence = cadenceFor(gap);
        return cadence != null && cadence == series.getIntervalDays();
    }

    /** The cadence a gap of {@code days} belongs to, or {@code null} if it belongs to none. */
    static Integer cadenceFor(long days) {
        for (int i = 0; i < CADENCE_DAYS.length; i++) {
            if (Math.abs(days - CADENCE_DAYS[i]) <= CADENCE_TOLERANCE_DAYS[i]) {
                return CADENCE_DAYS[i];
            }
        }
        return null;
    }

    private static BigDecimal lowerBound(BigDecimal amount, BigDecimal tolerance) {
        return amount.multiply(BigDecimal.ONE.subtract(tolerance));
    }

    private static BigDecimal upperBound(BigDecimal amount, BigDecimal tolerance) {
        return amount.multiply(BigDecimal.ONE.add(tolerance));
    }
}
