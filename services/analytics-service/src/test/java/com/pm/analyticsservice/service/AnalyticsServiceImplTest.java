package com.pm.analyticsservice.service;

import com.pm.analyticsservice.dto.CategoryMover;
import com.pm.analyticsservice.dto.ForecastResponse;
import com.pm.analyticsservice.dto.OverviewResponse;
import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.entity.SpendingModel;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.repository.SpendingModelRepository;
import com.pm.analyticsservice.service.impl.AnalyticsServiceImpl;
import com.pm.analyticsservice.summarizer.TemplateSummarizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceImplTest {

    private MonthlyCategoryRollupRepository repo;
    private SpendingModelRepository modelRepo;
    private AnalyticsServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(MonthlyCategoryRollupRepository.class);
        modelRepo = mock(SpendingModelRepository.class);
        // No trained model by default: these cases assert the run-rate behaviour, which stays
        // the answer for every user until the trainer has fitted one.
        when(modelRepo.findByUserIdAndCurrency(any(), any())).thenReturn(Optional.empty());
        // Real template summarizer: deterministic, no network.
        service = new AnalyticsServiceImpl(repo, modelRepo, new TemplateSummarizer());
    }

    @Test
    void overviewComputesTotalsSavingsRateAndExpenseChange() {
        // This month (2020-02): income 1000 (Salary), expense 700 (Food).
        when(repo.findByUserIdAndYearMonth(42L, "2020-02")).thenReturn(List.of(
                rollup("2020-02", 1L, "INCOME", "1000.00", 1),
                rollup("2020-02", 4L, "EXPENSE", "700.00", 3)));
        // Prior month (2020-01): expense 500 (Food), no income.
        when(repo.findByUserIdAndYearMonth(42L, "2020-01")).thenReturn(List.of(
                rollup("2020-01", 4L, "EXPENSE", "500.00", 2)));

        OverviewResponse ov = service.overview(42L, 2020, 2, "USD");

        assertThat(ov.income()).isEqualByComparingTo("1000.00");
        assertThat(ov.expense()).isEqualByComparingTo("700.00");
        assertThat(ov.net()).isEqualByComparingTo("300.00");
        assertThat(ov.savingsRate()).isEqualTo(30.0);
        // prior income was 0 => change undefined (null); expense rose 500 -> 700 = +40%.
        assertThat(ov.incomeChangePct()).isNull();
        assertThat(ov.expenseChangePct()).isEqualTo(40.0);

        assertThat(ov.topMovers()).hasSize(1);
        CategoryMover mover = ov.topMovers().get(0);
        assertThat(mover.categoryName()).isEqualTo("Food & Dining");
        assertThat(mover.amount()).isEqualByComparingTo("700.00");
        assertThat(mover.prevAmount()).isEqualByComparingTo("500.00");
        assertThat(mover.changePct()).isEqualTo(40.0);
    }

    @Test
    void forecastForPastMonthProjectsToActual() {
        // A completed month: the whole month elapsed, so the projection equals the actual.
        when(repo.findByUserIdAndYearMonth(42L, "2020-01")).thenReturn(List.of(
                rollup("2020-01", 4L, "EXPENSE", "620.00", 5)));

        ForecastResponse f = service.forecast(42L, 2020, 1, "USD");

        assertThat(f.daysInMonth()).isEqualTo(31);
        assertThat(f.dayOfMonth()).isEqualTo(31);
        assertThat(f.expenseToDate()).isEqualByComparingTo("620.00");
        assertThat(f.projectedExpense()).isEqualByComparingTo("620.00");
        assertThat(f.dailyAverage()).isEqualByComparingTo("20.00");
        // A finished month is history, not a forecast — the model is never consulted for it.
        assertThat(f.method()).isEqualTo("RUN_RATE");
        assertThat(f.projectedLow()).isNull();
    }

    @Test
    void forecastFallsBackToTheRunRateWhenNoModelHasBeenTrained() {
        YearMonth thisMonth = YearMonth.now();
        when(repo.findByUserIdAndYearMonth(42L, thisMonth.toString())).thenReturn(List.of(
                rollup(thisMonth.toString(), 4L, "EXPENSE", "300.00", 5)));

        ForecastResponse f = service.forecast(42L, thisMonth.getYear(), thisMonth.getMonthValue(), "USD");

        // This is the state every account is in until the nightly trainer has fitted it, and
        // the state the whole platform stays in while the feature flag is off.
        assertThat(f.method()).isEqualTo("RUN_RATE");
        assertThat(f.projectedLow()).isNull();
        assertThat(f.projectedHigh()).isNull();
    }

    @Test
    void forecastUsesTheTrainedModelForTheDaysStillToCome() {
        YearMonth thisMonth = YearMonth.now();
        int today = LocalDate.now().getDayOfMonth();
        // On the last day of a month there are no remaining days to model, so the run rate is
        // the correct answer and there is nothing to assert here. Skipping beats a test that
        // silently passes for the wrong reason one day in thirty.
        assumeTrue(today < thisMonth.lengthOfMonth(), "no days left in the month to project");

        when(repo.findByUserIdAndYearMonth(42L, thisMonth.toString())).thenReturn(List.of(
                rollup(thisMonth.toString(), 4L, "EXPENSE", "300.00", 5)));
        // A flat model: level 10/day, no trend, no weekly shape, trained up to yesterday.
        when(modelRepo.findByUserIdAndCurrency(42L, "USD"))
                .thenReturn(Optional.of(model(10.0, 2.0, LocalDate.now().minusDays(1))));

        ForecastResponse f = service.forecast(42L, thisMonth.getYear(), thisMonth.getMonthValue(), "USD");

        int remainingDays = thisMonth.lengthOfMonth() - today;
        assertThat(f.method()).isEqualTo("MODEL");
        // 300 already spent plus 10 for each day still to come — a number the run rate cannot
        // produce, since it would scale 300 by the elapsed fraction instead.
        assertThat(f.projectedExpense())
                .isEqualByComparingTo(BigDecimal.valueOf(300 + 10L * remainingDays));
        // The band is sigma * sqrt(remaining days), never narrower than what is already spent.
        assertThat(f.projectedLow()).isNotNull();
        assertThat(f.projectedHigh()).isGreaterThan(f.projectedExpense());
        assertThat(f.projectedLow()).isGreaterThanOrEqualTo(f.expenseToDate());
    }

    /** A model with a flat week, so the arithmetic in the test above stays legible. */
    private SpendingModel model(double level, double sigma, LocalDate trainedUpto) {
        BigDecimal one = BigDecimal.ONE;
        return SpendingModel.builder()
                .id(UUID.randomUUID())
                .userId(42L)
                .currency("USD")
                .levelValue(BigDecimal.valueOf(level))
                .trendValue(BigDecimal.ZERO)
                .dowMon(one).dowTue(one).dowWed(one).dowThu(one)
                .dowFri(one).dowSat(one).dowSun(one)
                .sigma(BigDecimal.valueOf(sigma))
                .sampleDays(60)
                .trainedUpto(trainedUpto)
                .trainedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void categoriesComputeShareWithinType() {
        when(repo.findByUserIdAndYearMonthBetween(42L, "2020-02", "2020-02")).thenReturn(List.of(
                rollup("2020-02", 4L, "EXPENSE", "750.00", 3),
                rollup("2020-02", 5L, "EXPENSE", "250.00", 1)));

        var slices = service.categories(42L, "2020-02", "2020-02", "USD");

        assertThat(slices).hasSize(2);
        // Sorted by total desc: Food (750, 75%) then Transport (250, 25%).
        assertThat(slices.get(0).categoryName()).isEqualTo("Food & Dining");
        assertThat(slices.get(0).share()).isEqualTo(75.0);
        assertThat(slices.get(1).categoryName()).isEqualTo("Transport");
        assertThat(slices.get(1).share()).isEqualTo(25.0);
    }

    @Test
    void summaryUsesTemplateAndIsNotAiGenerated() {
        when(repo.findByUserIdAndYearMonth(42L, "2020-02")).thenReturn(List.of(
                rollup("2020-02", 1L, "INCOME", "1000.00", 1),
                rollup("2020-02", 4L, "EXPENSE", "700.00", 3)));
        when(repo.findByUserIdAndYearMonth(42L, "2020-01")).thenReturn(List.of());

        var summary = service.summary(42L, 2020, 2, "USD");

        assertThat(summary.aiGenerated()).isFalse();
        assertThat(summary.summary()).contains("February 2020");
        assertThat(summary.yearMonth()).isEqualTo("2020-02");
    }

    private MonthlyCategoryRollup rollup(String ym, Long categoryId, String type, String amount, int count) {
        return MonthlyCategoryRollup.builder()
                .id(UUID.randomUUID()).userId(42L).yearMonth(ym).categoryId(categoryId)
                .type(type).currency("USD").totalAmount(new BigDecimal(amount)).txnCount(count)
                .build();
    }
}
