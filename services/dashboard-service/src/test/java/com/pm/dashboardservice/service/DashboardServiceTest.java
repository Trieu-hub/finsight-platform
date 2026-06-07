package com.pm.dashboardservice.service;

import com.pm.dashboardservice.client.BudgetClient;
import com.pm.dashboardservice.client.TransactionClient;
import com.pm.dashboardservice.client.UserClient;
import com.pm.dashboardservice.client.dto.BudgetDto;
import com.pm.dashboardservice.client.dto.CategorySummaryDto;
import com.pm.dashboardservice.client.dto.MonthlySummaryDto;
import com.pm.dashboardservice.client.dto.TransactionDto;
import com.pm.dashboardservice.client.dto.UserProfileDto;
import com.pm.dashboardservice.dto.BudgetProgressItem;
import com.pm.dashboardservice.dto.BudgetProgressResponse;
import com.pm.dashboardservice.dto.DashboardSummaryResponse;
import com.pm.dashboardservice.dto.OverviewResponse;
import com.pm.dashboardservice.dto.RecentTransactionResponse;
import com.pm.dashboardservice.dto.TopCategoryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final String AUTH = "Bearer token";

    @Mock BudgetClient budgetClient;
    @Mock TransactionClient transactionClient;
    @Mock UserClient userClient;

    @InjectMocks DashboardService service;

    private BudgetDto budget(long categoryId, String name, String limit) {
        return new BudgetDto(java.util.UUID.randomUUID(), name, categoryId, "MONTHLY",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), new BigDecimal(limit), "USD");
    }

    private CategorySummaryDto summary(long categoryId, String type, String total) {
        return new CategorySummaryDto(categoryId, "cat" + categoryId, type, new BigDecimal(total), 1);
    }

    @Test
    void budgetProgress_joinsLimitWithExpenseSpend() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of(budget(10, "Food", "200")));
        when(transactionClient.categorySummary(eq(AUTH), any(), any()))
                .thenReturn(List.of(summary(10, "EXPENSE", "50")));

        BudgetProgressResponse resp = service.budgetProgress(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(resp.items()).hasSize(1);
        BudgetProgressItem item = resp.items().get(0);
        assertThat(item.spentAmount()).isEqualByComparingTo("50");
        assertThat(item.remainingAmount()).isEqualByComparingTo("150");
        assertThat(item.percentUsed()).isEqualByComparingTo("25.00");
    }

    @Test
    void budgetProgress_ignoresIncomeRows_andZeroesUnspentBudgets() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of(budget(20, "Salary cat", "1000")));
        // An INCOME row in the same category must NOT count as spend.
        when(transactionClient.categorySummary(eq(AUTH), any(), any()))
                .thenReturn(List.of(summary(20, "INCOME", "5000")));

        BudgetProgressResponse resp = service.budgetProgress(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(resp.items().get(0).spentAmount()).isEqualByComparingTo("0");
        assertThat(resp.items().get(0).percentUsed()).isEqualByComparingTo("0");
    }

    @Test
    void budgetProgress_defaultsWindowToCurrentMonth_whenDatesNull() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of());
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of());

        BudgetProgressResponse resp = service.budgetProgress(AUTH, null, null);

        YearMonth now = YearMonth.now();
        assertThat(resp.fromDate()).isEqualTo(now.atDay(1));
        assertThat(resp.toDate()).isEqualTo(now.atEndOfMonth());
    }

    @Test
    void budgetProgress_roundsPercentToTwoDecimals() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of(budget(10, "Food", "3")));
        when(transactionClient.categorySummary(eq(AUTH), any(), any()))
                .thenReturn(List.of(summary(10, "EXPENSE", "1")));

        BudgetProgressResponse resp = service.budgetProgress(AUTH, LocalDate.now(), LocalDate.now());

        assertThat(resp.items().get(0).percentUsed()).isEqualByComparingTo("33.33");
    }

    // ── recent transactions ──────────────────────────────────────────────────────

    private TransactionDto txn(String id, String type, String amount) {
        return new TransactionDto(java.util.UUID.fromString(id), type, new BigDecimal(amount),
                "USD", 4L, "desc", LocalDate.of(2026, 6, 7));
    }

    @Test
    void recentTransactions_defaultLimit_callsClientWithFive_andMapsFields() {
        TransactionDto dto = txn("11111111-1111-1111-1111-111111111111", "EXPENSE", "100");
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of(dto));

        List<RecentTransactionResponse> result = service.recentTransactions(AUTH, 5);

        verify(transactionClient).recentTransactions(AUTH, 5);
        assertThat(result).hasSize(1);
        RecentTransactionResponse r = result.get(0);
        assertThat(r.transactionId()).isEqualTo(dto.id());   // id -> transactionId
        assertThat(r.type()).isEqualTo("EXPENSE");
        assertThat(r.amount()).isEqualByComparingTo("100");
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.categoryId()).isEqualTo(4L);
        assertThat(r.transactionDate()).isEqualTo(LocalDate.of(2026, 6, 7));
    }

    @Test
    void recentTransactions_customLimit_passedThrough() {
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of());

        service.recentTransactions(AUTH, 20);

        verify(transactionClient).recentTransactions(AUTH, 20);
    }

    @Test
    void recentTransactions_limitOver100_clampedTo100() {
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of());

        service.recentTransactions(AUTH, 500);

        verify(transactionClient).recentTransactions(AUTH, 100);
    }

    @Test
    void recentTransactions_limitZeroOrNegative_clampedToOne() {
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of());

        service.recentTransactions(AUTH, 0);
        verify(transactionClient).recentTransactions(AUTH, 1);

        service.recentTransactions(AUTH, -7);
        verify(transactionClient, org.mockito.Mockito.times(2)).recentTransactions(AUTH, 1);
    }

    @Test
    void recentTransactions_emptyResponse_returnsEmptyList() {
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of());

        assertThat(service.recentTransactions(AUTH, 5)).isEmpty();
    }

    // ── top categories ───────────────────────────────────────────────────────────

    @Test
    void topCategories_filtersExpense_sortsDesc_andLimits() {
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(4, "EXPENSE", "150"),
                summary(5, "EXPENSE", "300"),
                summary(6, "EXPENSE", "50"),
                summary(1, "INCOME", "1000")));   // must be excluded

        List<TopCategoryResponse> top = service.topCategories(AUTH, 2);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).categoryId()).isEqualTo(5L);
        assertThat(top.get(0).categoryName()).isEqualTo("cat5");
        assertThat(top.get(0).amount()).isEqualByComparingTo("300");
        assertThat(top.get(1).categoryId()).isEqualTo(4L);
        assertThat(top.get(1).amount()).isEqualByComparingTo("150");
        assertThat(top).noneMatch(t -> t.categoryId() == 1L); // income excluded
    }

    @Test
    void topCategories_limitLargerThanCount_returnsAllSorted() {
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(4, "EXPENSE", "10"),
                summary(5, "EXPENSE", "20")));

        List<TopCategoryResponse> top = service.topCategories(AUTH, 10);

        assertThat(top).extracting(TopCategoryResponse::categoryId).containsExactly(5L, 4L);
    }

    @Test
    void topCategories_noExpense_returnsEmptyList() {
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(1, "INCOME", "500")));

        assertThat(service.topCategories(AUTH, 5)).isEmpty();
    }

    @Test
    void topCategories_zeroOrNegativeLimit_returnsEmptyList_withoutError() {
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(4, "EXPENSE", "10")));

        assertThat(service.topCategories(AUTH, 0)).isEmpty();
        assertThat(service.topCategories(AUTH, -3)).isEmpty();
    }

    // ── summary ────────────────────────────────────────────────────────────────

    @Test
    void summary_computesIncomeExpenseBalanceAndUtilization() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of(
                budget(4, "Food", "500"), budget(5, "Transport", "200"))); // totalLimit = 700
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(4, "EXPENSE", "150"),
                summary(5, "EXPENSE", "50"),
                summary(9, "EXPENSE", "80"),    // unbudgeted: counts in expense, NOT in utilization
                summary(1, "INCOME", "1000")));

        DashboardSummaryResponse resp = service.summary(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(resp.totalIncome()).isEqualByComparingTo("1000");
        assertThat(resp.totalExpense()).isEqualByComparingTo("280");   // 150+50+80
        assertThat(resp.remainingBalance()).isEqualByComparingTo("720");
        assertThat(resp.budgetUtilization().totalLimit()).isEqualByComparingTo("700");
        assertThat(resp.budgetUtilization().totalSpent()).isEqualByComparingTo("200"); // 150+50 only
        assertThat(resp.budgetUtilization().utilizationPercent()).isEqualByComparingTo("28.57"); // 200/700
    }

    @Test
    void summary_noBudgets_utilizationIsZero_butKpisStillComputed() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of());
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(4, "EXPENSE", "100"),
                summary(1, "INCOME", "500")));

        DashboardSummaryResponse resp = service.summary(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(resp.totalIncome()).isEqualByComparingTo("500");
        assertThat(resp.totalExpense()).isEqualByComparingTo("100");
        assertThat(resp.budgetUtilization().totalLimit()).isEqualByComparingTo("0");
        assertThat(resp.budgetUtilization().totalSpent()).isEqualByComparingTo("0");
        assertThat(resp.budgetUtilization().utilizationPercent()).isEqualByComparingTo("0");
    }

    @Test
    void summary_defaultsWindowToCurrentMonth_whenDatesNull() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of());
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of());

        DashboardSummaryResponse resp = service.summary(AUTH, null, null);

        YearMonth now = YearMonth.now();
        assertThat(resp.fromDate()).isEqualTo(now.atDay(1));
        assertThat(resp.toDate()).isEqualTo(now.atEndOfMonth());
        assertThat(resp.totalIncome()).isEqualByComparingTo("0");
        assertThat(resp.totalExpense()).isEqualByComparingTo("0");
    }

    // ── trend ────────────────────────────────────────────────────────────────────

    private com.pm.dashboardservice.client.dto.TrendPointDto trendDay(String date, String income, String expense) {
        BigDecimal in = new BigDecimal(income);
        BigDecimal ex = new BigDecimal(expense);
        return new com.pm.dashboardservice.client.dto.TrendPointDto(LocalDate.parse(date), in, ex, in.subtract(ex));
    }

    @Test
    void trend_bucketsDailyPointsIntoMonths_sumsAndComputesBalance() {
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of(
                trendDay("2026-05-10", "100", "30"),
                trendDay("2026-05-20", "50", "20"),
                trendDay("2026-06-05", "200", "80")));

        List<com.pm.dashboardservice.dto.TrendPointResponse> trend =
                service.trend(AUTH, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 30), "month");

        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).period()).isEqualTo("2026-05");
        assertThat(trend.get(0).income()).isEqualByComparingTo("150");
        assertThat(trend.get(0).expense()).isEqualByComparingTo("50");
        assertThat(trend.get(0).balance()).isEqualByComparingTo("100");
        assertThat(trend.get(1).period()).isEqualTo("2026-06");
        assertThat(trend.get(1).income()).isEqualByComparingTo("200");
        assertThat(trend.get(1).expense()).isEqualByComparingTo("80");
        assertThat(trend.get(1).balance()).isEqualByComparingTo("120");
    }

    @Test
    void trend_emptyUpstream_returnsEmptyList() {
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of());

        assertThat(service.trend(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "month")).isEmpty();
    }

    @Test
    void trend_defaultsWindowToCurrentMonth_whenDatesNull() {
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of());

        service.trend(AUTH, null, null, "month");

        YearMonth now = YearMonth.now();
        verify(transactionClient).trend(AUTH, now.atDay(1), now.atEndOfMonth());
    }

    @Test
    void trend_monthBoundary_splitsAdjacentDaysAndOutputsChronologically_evenIfUpstreamUnordered() {
        // Upstream returned newest-first; output must still be oldest-first by month.
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of(
                trendDay("2026-02-01", "20", "5"),
                trendDay("2026-01-31", "10", "4")));

        List<com.pm.dashboardservice.dto.TrendPointResponse> trend =
                service.trend(AUTH, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), "month");

        assertThat(trend).extracting(com.pm.dashboardservice.dto.TrendPointResponse::period)
                .containsExactly("2026-01", "2026-02");
        assertThat(trend.get(0).income()).isEqualByComparingTo("10");
        assertThat(trend.get(1).income()).isEqualByComparingTo("20");
    }

    @Test
    void trend_dayGranularity_passesThroughPerDayWithIsoDatePeriod() {
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of(
                trendDay("2026-06-05", "200", "80"),
                trendDay("2026-06-06", "10", "10")));

        List<com.pm.dashboardservice.dto.TrendPointResponse> trend =
                service.trend(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "day");

        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).period()).isEqualTo("2026-06-05");
        assertThat(trend.get(0).balance()).isEqualByComparingTo("120");
        assertThat(trend.get(1).period()).isEqualTo("2026-06-06");
        assertThat(trend.get(1).balance()).isEqualByComparingTo("0");
    }

    @Test
    void trend_unknownGranularity_fallsBackToMonth() {
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of(
                trendDay("2026-06-05", "200", "80"),
                trendDay("2026-06-06", "10", "10")));

        List<com.pm.dashboardservice.dto.TrendPointResponse> trend =
                service.trend(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "weird");

        assertThat(trend).hasSize(1);
        assertThat(trend.get(0).period()).isEqualTo("2026-06");
        assertThat(trend.get(0).income()).isEqualByComparingTo("210");
    }

    @Test
    void trend_nullMoneyFields_treatedAsZero() {
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of(
                new com.pm.dashboardservice.client.dto.TrendPointDto(LocalDate.of(2026, 6, 5), null, null, null),
                trendDay("2026-06-06", "10", "4")));

        List<com.pm.dashboardservice.dto.TrendPointResponse> trend =
                service.trend(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "month");

        assertThat(trend).hasSize(1);
        assertThat(trend.get(0).income()).isEqualByComparingTo("10");
        assertThat(trend.get(0).expense()).isEqualByComparingTo("4");
        assertThat(trend.get(0).balance()).isEqualByComparingTo("6");
    }

    @Test
    void trend_aggregationPrecision_isExact() {
        // No division happens in trend (sum/subtract only) — summation stays exact.
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of(
                trendDay("2026-06-05", "0.10", "0.00"),
                trendDay("2026-06-06", "0.20", "0.00")));

        List<com.pm.dashboardservice.dto.TrendPointResponse> trend =
                service.trend(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "month");

        assertThat(trend.get(0).income()).isEqualByComparingTo("0.30");
    }

    // ── composite ────────────────────────────────────────────────────────────────

    private UserProfileDto profile(String fullName) {
        return new UserProfileDto(1L, fullName, null, null, null, null, null);
    }

    @Test
    void composite_assemblesAllBlocks_andFetchesEachUpstreamExactlyOnce() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of(budget(10, "Food", "200")));
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(10, "EXPENSE", "50"),
                summary(1, "INCOME", "300")));
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of(
                trendDay("2026-06-05", "300", "50")));
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of(
                txn("11111111-1111-1111-1111-111111111111", "EXPENSE", "50")));
        when(userClient.me(AUTH)).thenReturn(profile("Nguyen"));

        com.pm.dashboardservice.dto.DashboardResponse resp =
                service.composite(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 5);

        assertThat(resp.profile().fullName()).isEqualTo("Nguyen");
        assertThat(resp.summary().totalIncome()).isEqualByComparingTo("300");
        assertThat(resp.summary().totalExpense()).isEqualByComparingTo("50");
        assertThat(resp.summary().remainingBalance()).isEqualByComparingTo("250");
        assertThat(resp.budgetOverview()).hasSize(1);
        assertThat(resp.budgetOverview().get(0).spentAmount()).isEqualByComparingTo("50");
        assertThat(resp.topCategories()).hasSize(1);
        assertThat(resp.topCategories().get(0).categoryId()).isEqualTo(10L);
        assertThat(resp.recentTransactions()).hasSize(1);
        assertThat(resp.trend()).hasSize(1);
        assertThat(resp.trend().get(0).period()).isEqualTo("2026-06");

        // Fetch-once: each upstream dataset hit exactly once (no duplicate budget /
        // categorySummary calls despite three blocks consuming that shared data).
        verify(budgetClient, org.mockito.Mockito.times(1)).listBudgets(AUTH);
        verify(transactionClient, org.mockito.Mockito.times(1)).categorySummary(eq(AUTH), any(), any());
        verify(transactionClient, org.mockito.Mockito.times(1)).trend(eq(AUTH), any(), any());
        verify(transactionClient, org.mockito.Mockito.times(1)).recentTransactions(eq(AUTH), anyInt());
        verify(userClient, org.mockito.Mockito.times(1)).me(AUTH);
    }

    @Test
    void composite_defaultsWindowToCurrentMonth_whenDatesNull() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of());
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of());
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of());
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of());
        when(userClient.me(AUTH)).thenReturn(profile("Nguyen"));

        service.composite(AUTH, null, null, 5);

        YearMonth now = YearMonth.now();
        verify(transactionClient).categorySummary(AUTH, now.atDay(1), now.atEndOfMonth());
        verify(transactionClient).trend(AUTH, now.atDay(1), now.atEndOfMonth());
    }

    @Test
    void composite_emptyData_returnsEmptyBlocks_andZeroSummary() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of());
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of());
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of());
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of());
        when(userClient.me(AUTH)).thenReturn(profile("Nguyen"));

        com.pm.dashboardservice.dto.DashboardResponse resp =
                service.composite(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 5);

        assertThat(resp.budgetOverview()).isEmpty();
        assertThat(resp.topCategories()).isEmpty();
        assertThat(resp.recentTransactions()).isEmpty();
        assertThat(resp.trend()).isEmpty();
        assertThat(resp.summary().totalIncome()).isEqualByComparingTo("0");
        assertThat(resp.summary().totalExpense()).isEqualByComparingTo("0");
        assertThat(resp.summary().budgetUtilization().utilizationPercent()).isEqualByComparingTo("0");
    }

    @Test
    void composite_nullProfile_tolerated_otherBlocksStillBuilt() {
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of(budget(10, "Food", "200")));
        when(transactionClient.categorySummary(eq(AUTH), any(), any())).thenReturn(List.of(
                summary(10, "EXPENSE", "50")));
        when(transactionClient.trend(eq(AUTH), any(), any())).thenReturn(List.of());
        when(transactionClient.recentTransactions(eq(AUTH), anyInt())).thenReturn(List.of());
        when(userClient.me(AUTH)).thenReturn(null); // no profile created yet

        com.pm.dashboardservice.dto.DashboardResponse resp =
                service.composite(AUTH, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 5);

        assertThat(resp.profile()).isNull();
        assertThat(resp.summary()).isNotNull();
        assertThat(resp.budgetOverview()).hasSize(1);
    }

    @Test
    void overview_composesProfileSummaryAndBudgets() {
        UserProfileDto profile = new UserProfileDto(1L, "Nguyen", null, null, null, null, null);
        MonthlySummaryDto monthly = new MonthlySummaryDto(new BigDecimal("100"), new BigDecimal("40"), new BigDecimal("60"));
        when(userClient.me(AUTH)).thenReturn(profile);
        when(transactionClient.monthly(eq(AUTH), any(Integer.class), any(Integer.class))).thenReturn(monthly);
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of(budget(10, "Food", "200")));

        OverviewResponse resp = service.overview(AUTH);

        assertThat(resp.profile().fullName()).isEqualTo("Nguyen");
        assertThat(resp.currentMonth().balance()).isEqualByComparingTo("60");
        assertThat(resp.budgets()).hasSize(1);
    }

    @Test
    void overview_toleratesMissingProfile() {
        when(userClient.me(AUTH)).thenReturn(null); // no profile created yet
        when(transactionClient.monthly(eq(AUTH), any(Integer.class), any(Integer.class)))
                .thenReturn(new MonthlySummaryDto(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        when(budgetClient.listBudgets(AUTH)).thenReturn(List.of());

        OverviewResponse resp = service.overview(AUTH);

        assertThat(resp.profile()).isNull();
        assertThat(resp.budgets()).isEmpty();
    }
}
