package com.pm.riskservice.service;

import com.pm.riskservice.entity.BudgetSnapshot;
import com.pm.riskservice.entity.Insight;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.insight.InsightType;
import com.pm.riskservice.repository.BudgetSnapshotRepository;
import com.pm.riskservice.repository.InsightRepository;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the three insight rules, with the repositories mocked so totals, category
 * sums and budgets can be set precisely. The transaction date {@code 2026-06-15} fixes the
 * current month (2026-06) and previous month (2026-05) ranges the service queries.
 *
 * <p>Unstubbed sum/lookup methods return 0 / empty, so a test that stubs only one rule's inputs
 * sees only that rule fire.
 */
class InsightServiceTest {

    private static final long USER = 4242L;
    private static final long CATEGORY = 7L;
    private static final String CUR = "USD";
    private static final LocalDate JUNE_START = LocalDate.parse("2026-06-01");
    private static final LocalDate JULY_START = LocalDate.parse("2026-07-01");
    private static final LocalDate MAY_START = LocalDate.parse("2026-05-01");
    private static final String GENERATED = "finsight.insights.generated";

    private ObservedExpenseRepository expenseRepository;
    private InsightRepository insightRepository;
    private BudgetSnapshotRepository budgetRepository;
    private SimpleMeterRegistry meterRegistry;
    private InsightService service;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ObservedExpenseRepository.class);
        insightRepository = mock(InsightRepository.class);
        budgetRepository = mock(BudgetSnapshotRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new InsightService(expenseRepository, insightRepository, budgetRepository, meterRegistry);
        when(insightRepository.existsByUserIdAndInsightTypeAndPeriodMonthAndSubjectId(
                any(), any(), any(), any())).thenReturn(false);
        when(insightRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- SPENDING_INCREASE --------------------------------------------------------------------

    @Test
    void spendingIncreaseFiresAtExactly30Percent() {
        stubUserTotals("1300", "1000"); // +30%

        List<Insight> result = service.evaluate(expenseOn("2026-06-15"));

        assertThat(result).hasSize(1);
        Insight insight = result.get(0);
        assertThat(insight.getInsightType()).isEqualTo(InsightType.SPENDING_INCREASE.name());
        assertThat(insight.getPeriodMonth()).isEqualTo("2026-06");
        assertThat(insight.getCategoryId()).isNull();
        assertThat(insight.getSubjectId()).isEqualTo(InsightService.USER_SUBJECT);
        assertThat(insight.getPreviousAmount()).isEqualByComparingTo("1000");
        assertThat(insight.getCurrentAmount()).isEqualByComparingTo("1300");
        assertThat(insight.getIncreasePct()).isEqualByComparingTo("30.00");
        assertThat(count(InsightType.SPENDING_INCREASE)).isEqualTo(1.0);
    }

    @Test
    void spendingIncreaseDoesNotFireBelow30Percent() {
        stubUserTotals("1299.99", "1000");
        assertThat(service.evaluate(expenseOn("2026-06-15"))).isEmpty();
        verify(insightRepository, never()).save(any());
    }

    @Test
    void spendingIncreaseNeedsAPreviousBaseline() {
        stubUserTotals("5000", "0");
        assertThat(service.evaluate(expenseOn("2026-06-15"))).isEmpty();
    }

    // --- CATEGORY_SURGE -----------------------------------------------------------------------

    @Test
    void categorySurgeFiresAtOrAbove50Percent() {
        stubCategoryTotals("1500", "1000"); // +50%

        List<Insight> result = service.evaluate(expenseOn("2026-06-15"));

        assertThat(result).hasSize(1);
        Insight insight = result.get(0);
        assertThat(insight.getInsightType()).isEqualTo(InsightType.CATEGORY_SURGE.name());
        assertThat(insight.getCategoryId()).isEqualTo(CATEGORY);
        assertThat(insight.getSubjectId()).isEqualTo(String.valueOf(CATEGORY));
        assertThat(insight.getPreviousAmount()).isEqualByComparingTo("1000");
        assertThat(insight.getCurrentAmount()).isEqualByComparingTo("1500");
        assertThat(insight.getIncreasePct()).isEqualByComparingTo("50.00");
        assertThat(count(InsightType.CATEGORY_SURGE)).isEqualTo(1.0);
    }

    @Test
    void categorySurgeDoesNotFireBelow50Percent() {
        stubCategoryTotals("1490", "1000"); // +49%
        assertThat(service.evaluate(expenseOn("2026-06-15"))).isEmpty();
    }

    // --- BUDGET_RISK --------------------------------------------------------------------------

    @Test
    void budgetRiskFiresWhenUtilizationExceeds80Percent() {
        BudgetSnapshot budget = budget("1000", "2026-06-01", "2026-06-30");
        when(budgetRepository.findActiveMatching(USER, CATEGORY, CUR, LocalDate.parse("2026-06-15")))
                .thenReturn(List.of(budget));
        when(expenseRepository.sumAmountForBudgetWindow(eq(USER), eq(CATEGORY), eq(CUR),
                eq(LocalDate.parse("2026-06-01")), eq(LocalDate.parse("2026-06-30"))))
                .thenReturn(new BigDecimal("900")); // 90% utilization

        List<Insight> result = service.evaluate(expenseOn("2026-06-15"));

        assertThat(result).hasSize(1);
        Insight insight = result.get(0);
        assertThat(insight.getInsightType()).isEqualTo(InsightType.BUDGET_RISK.name());
        assertThat(insight.getCategoryId()).isEqualTo(CATEGORY);
        assertThat(insight.getSubjectId()).isEqualTo(budget.getId().toString());
        assertThat(insight.getPreviousAmount()).isEqualByComparingTo("1000"); // limit
        assertThat(insight.getCurrentAmount()).isEqualByComparingTo("900");   // spent
        assertThat(insight.getIncreasePct()).isEqualByComparingTo("90.00");   // utilization
        assertThat(count(InsightType.BUDGET_RISK)).isEqualTo(1.0);
    }

    @Test
    void budgetRiskDoesNotFireAtExactly80Percent() {
        BudgetSnapshot budget = budget("1000", "2026-06-01", "2026-06-30");
        when(budgetRepository.findActiveMatching(any(), any(), any(), any()))
                .thenReturn(List.of(budget));
        when(expenseRepository.sumAmountForBudgetWindow(any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("800")); // exactly 80% — not "exceeds"

        assertThat(service.evaluate(expenseOn("2026-06-15"))).isEmpty();
        verify(insightRepository, never()).save(any());
    }

    // --- LOW_SAVINGS_RATE ---------------------------------------------------------------------

    @Test
    void lowSavingsRateFiresWhenExpensesReach80PercentOfIncome() {
        stubMonthIncome("1000");
        stubMonthExpenses("800"); // exactly 80% of income — "at least 80%"

        List<Insight> result = service.evaluate(expenseOn("2026-06-15"));

        assertThat(result).hasSize(1);
        Insight insight = result.get(0);
        assertThat(insight.getInsightType()).isEqualTo(InsightType.LOW_SAVINGS_RATE.name());
        assertThat(insight.getPeriodMonth()).isEqualTo("2026-06");
        assertThat(insight.getCategoryId()).isNull();
        assertThat(insight.getSubjectId()).isEqualTo(InsightService.USER_SUBJECT);
        assertThat(insight.getPreviousAmount()).isEqualByComparingTo("1000"); // income
        assertThat(insight.getCurrentAmount()).isEqualByComparingTo("800");   // expenses
        assertThat(insight.getIncreasePct()).isEqualByComparingTo("80.00");   // share of income spent
        assertThat(count(InsightType.LOW_SAVINGS_RATE)).isEqualTo(1.0);
    }

    @Test
    void lowSavingsRateDoesNotFireBelowThreshold() {
        stubMonthIncome("1000");
        stubMonthExpenses("799.99"); // 79.999% — below 80%
        assertThat(service.evaluate(expenseOn("2026-06-15"))).isEmpty();
        verify(insightRepository, never()).save(any());
    }

    @Test
    void lowSavingsRateNeedsPositiveIncome() {
        stubMonthIncome("0");
        stubMonthExpenses("5000");
        assertThat(service.evaluate(expenseOn("2026-06-15"))).isEmpty();
    }

    // --- INCOME recording ---------------------------------------------------------------------

    @Test
    void incomeEventIsRecordedButProducesNoInsight() {
        TransactionCreatedEvent income = new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", "2026-06-15T10:00:00Z",
                UUID.randomUUID(), USER, "INCOME", new BigDecimal("9999"),
                CUR, CATEGORY, "2026-06-15", 1L);

        assertThat(service.evaluate(income)).isEmpty();
        // Recorded into observed_expenses as the income side of the read-model...
        verify(expenseRepository).save(any());
        // ...but income itself is not an insight.
        verify(insightRepository, never()).save(any());
    }

    // --- cross-cutting ------------------------------------------------------------------------

    @Test
    void ignoresNonExpenseEvents() {
        TransactionCreatedEvent income = new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", "2026-06-15T10:00:00Z",
                UUID.randomUUID(), USER, "INCOME", new BigDecimal("9999"),
                CUR, CATEGORY, "2026-06-15", 1L);

        assertThat(service.evaluate(income)).isEmpty();
        verify(insightRepository, never()).save(any());
    }

    @Test
    void multipleInsightsCanFireForOneEvent() {
        stubUserTotals("1300", "1000");      // SPENDING_INCREASE +30%
        stubCategoryTotals("1500", "1000");  // CATEGORY_SURGE +50%
        BudgetSnapshot budget = budget("1000", "2026-06-01", "2026-06-30");
        when(budgetRepository.findActiveMatching(any(), any(), any(), any()))
                .thenReturn(List.of(budget));
        when(expenseRepository.sumAmountForBudgetWindow(any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("950")); // BUDGET_RISK 95%

        List<Insight> result = service.evaluate(expenseOn("2026-06-15"));

        assertThat(result).extracting(Insight::getInsightType)
                .containsExactlyInAnyOrder("SPENDING_INCREASE", "CATEGORY_SURGE", "BUDGET_RISK");
    }

    // --- helpers ------------------------------------------------------------------------------

    private void stubUserTotals(String current, String previous) {
        when(expenseRepository.sumAmountInDateRange(eq(USER), eq(JUNE_START), eq(JULY_START)))
                .thenReturn(new BigDecimal(current));
        when(expenseRepository.sumAmountInDateRange(eq(USER), eq(MAY_START), eq(JUNE_START)))
                .thenReturn(new BigDecimal(previous));
    }

    private void stubMonthIncome(String current) {
        when(expenseRepository.sumIncomeInDateRange(eq(USER), eq(JUNE_START), eq(JULY_START)))
                .thenReturn(new BigDecimal(current));
    }

    private void stubMonthExpenses(String current) {
        when(expenseRepository.sumAmountInDateRange(eq(USER), eq(JUNE_START), eq(JULY_START)))
                .thenReturn(new BigDecimal(current));
    }

    private void stubCategoryTotals(String current, String previous) {
        when(expenseRepository.sumAmountForCategoryInDateRange(
                eq(USER), eq(CATEGORY), eq(JUNE_START), eq(JULY_START)))
                .thenReturn(new BigDecimal(current));
        when(expenseRepository.sumAmountForCategoryInDateRange(
                eq(USER), eq(CATEGORY), eq(MAY_START), eq(JUNE_START)))
                .thenReturn(new BigDecimal(previous));
    }

    private BudgetSnapshot budget(String limit, String start, String end) {
        return new BudgetSnapshot(UUID.randomUUID(), USER, CATEGORY, CUR, new BigDecimal(limit),
                LocalDate.parse(start), LocalDate.parse(end), false, Instant.now());
    }

    private TransactionCreatedEvent expenseOn(String transactionDate) {
        return new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", Instant.now().toString(),
                UUID.randomUUID(), USER, "EXPENSE", new BigDecimal("100"),
                CUR, CATEGORY, transactionDate, 1L);
    }

    private double count(InsightType type) {
        return meterRegistry.find(GENERATED).tag("type", type.name()).counter().count();
    }
}
