package com.pm.riskservice.rule;

import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the expense and income rules and their crossing semantics, with the repository
 * mocked so the windowed aggregates can be set precisely.
 */
class RiskRuleEngineTest {

    private ObservedExpenseRepository repository;
    private RiskRuleEngine engine;

    @BeforeEach
    void setUp() {
        repository = mock(ObservedExpenseRepository.class);
        engine = new RiskRuleEngine(repository);
        // Defaults: unique event, no rapid burst, day total = this event's amount only.
        when(repository.existsById(any())).thenReturn(false);
    }

    @Test
    void highAmountExpenseFires() {
        stubWindow(1, "10000000");
        assertThat(engine.evaluate(expense(50L, "10000000")))
                .containsExactly(RiskRule.HIGH_AMOUNT_EXPENSE);
    }

    @Test
    void belowAllThresholdsFiresNothing() {
        stubWindow(1, "100.00");
        assertThat(engine.evaluate(expense(50L, "100.00"))).isEmpty();
    }

    @Test
    void rapidSpendingFiresOnTheFifthInWindow() {
        stubWindow(5, "100.00");
        assertThat(engine.evaluate(expense(50L, "100.00")))
                .containsExactly(RiskRule.RAPID_SPENDING);
    }

    @Test
    void rapidSpendingDoesNotRefireAfterTheFifth() {
        // Sixth+ expense in the window (count past the threshold) must not re-alert.
        stubWindow(6, "100.00");
        assertThat(engine.evaluate(expense(50L, "100.00"))).isEmpty();
    }

    @Test
    void largeDailySpendFiresOnTheCrossingEvent() {
        // Before this event the day total was 16M (<= 20M); this 5M expense pushes it to 21M.
        stubWindow(1, "21000000");
        assertThat(engine.evaluate(expense(50L, "5000000")))
                .containsExactly(RiskRule.LARGE_DAILY_SPEND);
    }

    @Test
    void largeDailySpendDoesNotRefireOncePastThreshold() {
        // Day total already over 20M before this event (before = 24M) — no second alert.
        stubWindow(1, "25000000");
        assertThat(engine.evaluate(expense(50L, "1000000"))).isEmpty();
    }

    @Test
    void multipleRulesCanFireForOneEvent() {
        // A 25M expense that is also the 5th in the window and crosses the daily threshold.
        stubWindow(5, "25000000");
        assertThat(engine.evaluate(expense(50L, "25000000")))
                .containsExactly(RiskRule.HIGH_AMOUNT_EXPENSE,
                        RiskRule.RAPID_SPENDING, RiskRule.LARGE_DAILY_SPEND);
    }

    // --- Income rules --------------------------------------------------------------------
    // Money arriving is evaluated too: an expense tracker should be as suspicious of unexplained
    // income as of unexplained spending. The income thresholds sit higher than the expense ones
    // on purpose — a salary is legitimately large, and reusing the expense threshold would alert
    // on every payday.

    @Test
    void highAmountIncomeFires() {
        stubIncomeWindow(1, "50000000");
        assertThat(engine.evaluate(income(50L, "50000000")))
                .containsExactly(RiskRule.HIGH_AMOUNT_INCOME);
    }

    @Test
    void ordinaryIncomeFiresNothing() {
        // 15M would have tripped the EXPENSE threshold (10M). As income it is just a good month.
        stubIncomeWindow(1, "15000000");
        assertThat(engine.evaluate(income(50L, "15000000"))).isEmpty();
    }

    @Test
    void rapidIncomeFiresOnTheFifthInWindow() {
        stubIncomeWindow(5, "100.00");
        assertThat(engine.evaluate(income(50L, "100.00")))
                .containsExactly(RiskRule.RAPID_INCOME);
    }

    @Test
    void largeDailyIncomeFiresOnTheCrossingEvent() {
        // Before this event the day's income was 80M (<= 100M); this 30M pushes it to 110M.
        stubIncomeWindow(1, "110000000");
        assertThat(engine.evaluate(income(50L, "30000000")))
                .containsExactly(RiskRule.LARGE_DAILY_INCOME);
    }

    @Test
    void incomeSpikeFiresAtThreeTimesTheUsersOwnMean() {
        stubIncomeWindow(1, "3000000");
        stubIncomeBaseline(12, "1000000"); // 12 prior incomes averaging 1M
        assertThat(engine.evaluate(income(50L, "3000000")))
                .containsExactly(RiskRule.INCOME_SPIKE);
    }

    @Test
    void incomeSpikeNeedsEnoughHistoryToHaveAMean() {
        // Same 3x jump, but on only 4 prior incomes the "mean" means nothing yet.
        stubIncomeWindow(1, "3000000");
        stubIncomeBaseline(4, "1000000");
        assertThat(engine.evaluate(income(50L, "3000000"))).isEmpty();
    }

    // --- The bar is the person, not a number -----------------------------------------------
    // Every test above leaves the baselines unstubbed, so they report "no history" and the engine
    // uses the flat thresholds — which is exactly the cold-start contract. These set a history and
    // check that the bar moves with it, in both directions.

    @Test
    void aHeavySpenderIsJudgedAgainstTheirOwnMeanNotAFlatTenMillion() {
        // Mean expense 5M over 12 observations => bar 25M. 15M clears the old flat 10M and would
        // have alerted before; for this user it is an ordinary Tuesday.
        stubWindow(1, "15000000");
        stubExpenseBaseline(12, "5000000");
        assertThat(engine.evaluate(expense(50L, "15000000"))).isEmpty();
    }

    @Test
    void andFiresOnceThatHigherBarIsPassed() {
        stubWindow(1, "25000000");
        stubExpenseBaseline(12, "5000000");
        stubDailyExpenseBaseline(20, "200000000"); // 10M/day mean => daily bar 50M, stays quiet
        assertThat(engine.evaluate(expense(50L, "25000000")))
                .containsExactly(RiskRule.HIGH_AMOUNT_EXPENSE);
    }

    @Test
    void aLightSpenderIsAlertedWellBelowTheFlatThreshold() {
        // Mean 300k => bar 1.5M. A 2M expense is a big deal for this person and the flat 10M
        // threshold would never once have told them so.
        stubWindow(1, "2000000");
        stubExpenseBaseline(12, "300000");
        assertThat(engine.evaluate(expense(50L, "2000000")))
                .containsExactly(RiskRule.HIGH_AMOUNT_EXPENSE);
    }

    @Test
    void theFloorStopsATinyBaselineFromAlertingOnACupOfCoffee() {
        // Mean 50k would put the bar at 250k. The floor (a tenth of the flat threshold) holds it
        // at 1M, so a 300k expense stays a HIGH-severity alert nobody wanted.
        stubWindow(1, "300000");
        stubExpenseBaseline(12, "50000");
        assertThat(engine.evaluate(expense(50L, "300000"))).isEmpty();
    }

    @Test
    void tooLittleHistoryFallsBackToTheFlatThreshold() {
        // Same light-spender mean, but four observations is not a baseline. The flat 10M applies,
        // so a new user is protected from their first transaction rather than from their tenth.
        stubWindow(1, "2000000");
        stubExpenseBaseline(4, "300000");
        assertThat(engine.evaluate(expense(50L, "2000000"))).isEmpty();
    }

    @Test
    void theAlertBarSitsAboveTheAnomalyDetectorsThreeTimes() {
        // 3x the user's mean is what UNUSUAL_TRANSACTION_AMOUNT records. Deliberately not enough
        // to raise a HIGH risk alert as well, or the two would be one rule wearing two names.
        stubWindow(1, "3000000");
        stubExpenseBaseline(12, "1000000");
        assertThat(engine.evaluate(expense(50L, "3000000"))).isEmpty();
    }

    @Test
    void largeDailySpendUsesTheUsersOwnDailyMean() {
        // 20M over 20 spending days => 1M/day mean => bar 5M. This 3M expense takes the day from
        // 3M to 6M and crosses it, at less than a third of the flat 20M.
        stubWindow(1, "6000000");
        stubDailyExpenseBaseline(20, "20000000");
        assertThat(engine.evaluate(expense(50L, "3000000")))
                .containsExactly(RiskRule.LARGE_DAILY_SPEND);
    }

    @Test
    void theDailyBarNeedsEnoughDaysBeforeItReplacesTheFlatOne() {
        // Three spending days is not a habit yet, so the flat 20M still applies and 6M is quiet.
        stubWindow(1, "6000000");
        stubDailyExpenseBaseline(3, "3000000");
        assertThat(engine.evaluate(expense(50L, "3000000"))).isEmpty();
    }

    @Test
    void incomeIsScaledToThePersonToo() {
        // Mean income 2M => bar 10M, far under the flat 50M. 12M clears both that and the 3x
        // spike factor, so the two income rules fire together — they answer different questions.
        stubIncomeWindow(1, "12000000");
        stubIncomeBaseline(12, "2000000");
        assertThat(engine.evaluate(income(50L, "12000000")))
                .containsExactly(RiskRule.HIGH_AMOUNT_INCOME, RiskRule.INCOME_SPIKE);
    }

    // --- Shared ---------------------------------------------------------------------------

    @Test
    void transferIsNotEvaluatedOrRecorded() {
        // TRANSFER moves money between the user's own wallets — it is neither income nor spending,
        // so no rule applies and it must not pollute the windowed aggregates.
        assertThat(engine.evaluate(event(50L, "TRANSFER", "99999999"))).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateEventIsSkipped() {
        when(repository.existsById(any())).thenReturn(true);
        assertThat(engine.evaluate(expense(50L, "10000000"))).isEmpty();
        verify(repository, never()).save(any());
    }

    /** Sets the windowed-count and daily-sum the repository reports after the save. */
    private void stubWindow(long windowCount, String dayTotal) {
        when(repository.countByUserIdAndOccurredAtBetween(any(), any(), any()))
                .thenReturn(windowCount);
        when(repository.sumAmountForDay(any(), any())).thenReturn(new BigDecimal(dayTotal));
    }

    /** The income equivalents. The baseline defaults to "no history", so no spike unless stubbed. */
    private void stubIncomeWindow(long windowCount, String dayTotal) {
        when(repository.countIncomeByUserIdAndOccurredAtBetween(any(), any(), any()))
                .thenReturn(windowCount);
        when(repository.sumIncomeForDay(any(), any())).thenReturn(new BigDecimal(dayTotal));
        stubIncomeBaseline(0, null);
    }

    // The projection mock is always built into a local first: Mockito's when() is stateful, and
    // stubbing the projection *inside* when(repository...) leaves the outer stubbing unfinished.

    /** Prior EXPENSE count and mean — what the per-transaction bar is derived from. */
    private void stubExpenseBaseline(long count, String average) {
        var stub = baseline(count, average);
        when(repository.expenseBaselineBefore(any(), any())).thenReturn(stub);
    }

    /** Prior EXPENSE total and the number of days it spans — the daily bar's input. */
    private void stubDailyExpenseBaseline(long days, String total) {
        var stub = dailyBaseline(days, total);
        when(repository.dailyExpenseBaselineBefore(any(), any())).thenReturn(stub);
    }

    private static ObservedExpenseRepository.ExpenseBaseline baseline(long count, String average) {
        ObservedExpenseRepository.ExpenseBaseline baseline =
                mock(ObservedExpenseRepository.ExpenseBaseline.class);
        when(baseline.getCount()).thenReturn(count);
        when(baseline.getAverage()).thenReturn(average == null ? null : new BigDecimal(average));
        return baseline;
    }

    private static ObservedExpenseRepository.DailyBaseline dailyBaseline(long days, String total) {
        ObservedExpenseRepository.DailyBaseline baseline =
                mock(ObservedExpenseRepository.DailyBaseline.class);
        when(baseline.getDays()).thenReturn(days);
        when(baseline.getTotal()).thenReturn(total == null ? null : new BigDecimal(total));
        return baseline;
    }

    private void stubIncomeBaseline(long count, String average) {
        var stub = baseline(count, average);
        when(repository.incomeBaselineBefore(any(), any())).thenReturn(stub);
    }

    private TransactionCreatedEvent expense(long userId, String amount) {
        return event(userId, "EXPENSE", amount);
    }

    private TransactionCreatedEvent income(long userId, String amount) {
        return event(userId, "INCOME", amount);
    }

    private TransactionCreatedEvent event(long userId, String type, String amount) {
        return new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", Instant.now().toString(),
                UUID.randomUUID(), userId, type, new BigDecimal(amount),
                "USD", 4L, LocalDate.now().toString(), 7L);
    }
}
