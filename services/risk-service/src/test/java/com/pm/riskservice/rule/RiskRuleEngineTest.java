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
 * Unit tests for the three rules and their crossing semantics, with the repository mocked
 * so the windowed aggregates can be set precisely.
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

    @Test
    void nonExpenseIsNotEvaluatedOrRecorded() {
        assertThat(engine.evaluate(event(50L, "INCOME", "99999999"))).isEmpty();
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

    private TransactionCreatedEvent expense(long userId, String amount) {
        return event(userId, "EXPENSE", amount);
    }

    private TransactionCreatedEvent event(long userId, String type, String amount) {
        return new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", Instant.now().toString(),
                UUID.randomUUID(), userId, type, new BigDecimal(amount),
                "USD", 4L, LocalDate.now().toString(), 7L);
    }
}
