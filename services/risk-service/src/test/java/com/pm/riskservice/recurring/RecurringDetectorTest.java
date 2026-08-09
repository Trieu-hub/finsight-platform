package com.pm.riskservice.recurring;

import com.pm.riskservice.entity.RecurringSeries;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import com.pm.riskservice.repository.RecurringSeriesRepository;
import com.pm.riskservice.rule.RiskRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for recurring-charge detection: opening a series from two charges one cadence
 * apart, confirming it on the third, reporting a price rise, and the several ways a charge
 * that merely looks similar must NOT be folded into a series.
 */
class RecurringDetectorTest {

    private static final long USER = 42L;
    private static final long CATEGORY = 4L;
    private static final String CURRENCY = "USD";
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    private RecurringSeriesRepository seriesRepository;
    private ObservedExpenseRepository expenseRepository;
    private RecurringDetector detector;

    @BeforeEach
    void setUp() {
        seriesRepository = mock(RecurringSeriesRepository.class);
        expenseRepository = mock(ObservedExpenseRepository.class);
        detector = new RecurringDetector(seriesRepository, expenseRepository);
        when(seriesRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    // --- Opening a series ---------------------------------------------------------------------

    @Test
    void opensASeriesWhenASimilarChargeWasOneMonthAgo() {
        priorSimilarCharge(TODAY.minusDays(30));

        assertThat(evaluate("9.99", TODAY)).isEmpty();

        RecurringSeries opened = savedSeries();
        assertThat(opened.getIntervalDays()).isEqualTo(30);
        assertThat(opened.getOccurrences()).isEqualTo(2);
        assertThat(opened.getFirstSeen()).isEqualTo(TODAY.minusDays(30));
        assertThat(opened.getLastSeen()).isEqualTo(TODAY);
        assertThat(opened.getNextExpected()).isEqualTo(TODAY.plusDays(30));
        assertThat(opened.isActive()).isTrue();
    }

    @Test
    void aMonthlyGapIsStillMonthlyAtTwentyEightAndThirtyOneDays() {
        // Calendar months are not 30 days long; February and a billing date over a weekend
        // both have to keep matching or no real subscription would ever be recognised.
        assertThat(RecurringDetector.cadenceFor(28)).isEqualTo(30);
        assertThat(RecurringDetector.cadenceFor(31)).isEqualTo(30);
        assertThat(RecurringDetector.cadenceFor(7)).isEqualTo(7);
        assertThat(RecurringDetector.cadenceFor(91)).isEqualTo(91);
    }

    @Test
    void opensNothingWhenTheGapMatchesNoCadence() {
        priorSimilarCharge(TODAY.minusDays(17));

        assertThat(evaluate("9.99", TODAY)).isEmpty();
        verify(seriesRepository, never()).save(any());
    }

    @Test
    void opensNothingWithoutAnEarlierChargeOfASimilarAmount() {
        when(expenseRepository.findSimilarExpenseDatesBefore(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(evaluate("9.99", TODAY)).isEmpty();
        verify(seriesRepository, never()).save(any());
    }

    @Test
    void ignoresAChargeWithNoCategoryOrCurrency() {
        // Identity here is (user, category, currency, amount); without two of those there is
        // nothing to recognise a series by.
        assertThat(detector.evaluate(USER, null, CURRENCY, new BigDecimal("9.99"),
                UUID.randomUUID(), TODAY)).isEmpty();
        assertThat(detector.evaluate(USER, CATEGORY, null, new BigDecimal("9.99"),
                UUID.randomUUID(), TODAY)).isEmpty();
        verify(seriesRepository, never()).save(any());
    }

    // --- Continuing a series ------------------------------------------------------------------

    @Test
    void confirmsTheSeriesOnTheThirdCharge() {
        activeSeries("9.99", 30, TODAY.minusDays(30), 2);

        assertThat(evaluate("9.99", TODAY))
                .containsExactly(RiskRule.RECURRING_CHARGE_DETECTED);
        RecurringSeries saved = savedSeries();
        assertThat(saved.getOccurrences()).isEqualTo(3);
        assertThat(saved.getNextExpected()).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    void doesNotReconfirmOnTheFourthCharge() {
        activeSeries("9.99", 30, TODAY.minusDays(30), 3);

        assertThat(evaluate("9.99", TODAY)).isEmpty();
        assertThat(savedSeries().getOccurrences()).isEqualTo(4);
    }

    @Test
    void reportsAPriceRiseOnAnEstablishedSeries() {
        activeSeries("10.00", 30, TODAY.minusDays(30), 3);

        // 11.50 is 1.15× the established price — the threshold, and still inside the 25%
        // match band, which is the whole reason those two numbers differ.
        assertThat(evaluate("11.50", TODAY))
                .containsExactly(RiskRule.RECURRING_PRICE_INCREASE);
        // Re-based, so the next charge at the new price does not report the same rise again.
        assertThat(savedSeries().getTypicalAmount()).isEqualByComparingTo("11.50");
    }

    @Test
    void treatsSmallDriftAsTheSamePrice() {
        activeSeries("10.00", 30, TODAY.minusDays(30), 3);

        assertThat(evaluate("11.00", TODAY)).isEmpty();
        assertThat(savedSeries().getTypicalAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void rebasesSilentlyWhenTheChargeGetsCheaper() {
        activeSeries("10.00", 30, TODAY.minusDays(30), 3);

        // A subscription getting cheaper is not a risk — but leaving the old price on the row
        // would make the next ordinary charge look like a drop forever.
        assertThat(evaluate("8.00", TODAY)).isEmpty();
        assertThat(savedSeries().getTypicalAmount()).isEqualByComparingTo("8.00");
    }

    @Test
    void doesNotCountAChargeThatArrivedOffCadence() {
        // Same category, same price, but eleven days after the last one: an ordinary purchase
        // that happens to resemble the subscription, not the subscription.
        activeSeries("9.99", 30, TODAY.minusDays(11), 3);

        assertThat(evaluate("9.99", TODAY)).isEmpty();
        verify(seriesRepository, never()).save(any());
    }

    @Test
    void doesNotSeedASecondSeriesWhenOneMatchedOnPriceButNotOnCadence() {
        // Seeding here would leave two series competing for the next charge.
        activeSeries("9.99", 30, TODAY.minusDays(11), 3);

        detector.evaluate(USER, CATEGORY, CURRENCY, new BigDecimal("9.99"),
                UUID.randomUUID(), TODAY);

        verify(expenseRepository, never()).findSimilarExpenseDatesBefore(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any());
        verify(seriesRepository, never()).save(any());
    }

    // --- helpers --------------------------------------------------------------------------------

    private List<RiskRule> evaluate(String amount, LocalDate date) {
        return detector.evaluate(USER, CATEGORY, CURRENCY, new BigDecimal(amount),
                UUID.randomUUID(), date);
    }

    /** Stubs an earlier charge of a similar amount on {@code date}. */
    private void priorSimilarCharge(LocalDate date) {
        when(expenseRepository.findSimilarExpenseDatesBefore(
                anyLong(), anyLong(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(date));
    }

    /** Stubs one live series with the given established price, cadence, last charge and count. */
    private void activeSeries(String typicalAmount, int intervalDays, LocalDate lastSeen,
                              int occurrences) {
        RecurringSeries series = new RecurringSeries(
                UUID.randomUUID(), USER, CATEGORY, CURRENCY, new BigDecimal(typicalAmount),
                intervalDays, lastSeen.minusDays(intervalDays), lastSeen, UUID.randomUUID(),
                Instant.parse("2026-01-01T00:00:00Z"));
        for (int i = 2; i < occurrences; i++) {
            series.recordOccurrence(lastSeen, UUID.randomUUID(), Instant.now());
        }
        when(seriesRepository.findActiveMatches(anyLong(), anyLong(), anyString(), any(), any(), any()))
                .thenReturn(List.of(series));
    }

    private RecurringSeries savedSeries() {
        ArgumentCaptor<RecurringSeries> captor = ArgumentCaptor.forClass(RecurringSeries.class);
        verify(seriesRepository).save(captor.capture());
        return captor.getValue();
    }
}
