package com.pm.analyticsservice.service;

import com.pm.analyticsservice.dto.CategoryMover;
import com.pm.analyticsservice.dto.ForecastResponse;
import com.pm.analyticsservice.dto.OverviewResponse;
import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.service.impl.AnalyticsServiceImpl;
import com.pm.analyticsservice.summarizer.TemplateSummarizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceImplTest {

    private MonthlyCategoryRollupRepository repo;
    private AnalyticsServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(MonthlyCategoryRollupRepository.class);
        // Real template summarizer: deterministic, no network.
        service = new AnalyticsServiceImpl(repo, new TemplateSummarizer());
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
