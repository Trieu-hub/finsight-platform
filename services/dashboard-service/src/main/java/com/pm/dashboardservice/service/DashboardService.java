package com.pm.dashboardservice.service;

import com.pm.dashboardservice.client.BudgetClient;
import com.pm.dashboardservice.client.TransactionClient;
import com.pm.dashboardservice.client.UserClient;
import com.pm.dashboardservice.client.dto.BudgetDto;
import com.pm.dashboardservice.client.dto.CategorySummaryDto;
import com.pm.dashboardservice.client.dto.MonthlySummaryDto;
import com.pm.dashboardservice.client.dto.TransactionDto;
import com.pm.dashboardservice.client.dto.TrendPointDto;
import com.pm.dashboardservice.client.dto.UserProfileDto;
import com.pm.dashboardservice.dto.BudgetProgressItem;
import com.pm.dashboardservice.dto.BudgetProgressResponse;
import com.pm.dashboardservice.dto.BudgetUtilization;
import com.pm.dashboardservice.dto.DashboardResponse;
import com.pm.dashboardservice.dto.DashboardSummaryResponse;
import com.pm.dashboardservice.dto.OverviewResponse;
import com.pm.dashboardservice.dto.RecentTransactionResponse;
import com.pm.dashboardservice.dto.TopCategoryResponse;
import com.pm.dashboardservice.dto.TrendPointResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Read-only aggregation. Each method relays the caller's bearer token to the upstream
 * services (which validate it and scope every figure to that user). The dashboard
 * itself holds no per-user state.
 */
@Service
public class DashboardService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BudgetClient budgetClient;
    private final TransactionClient transactionClient;
    private final UserClient userClient;

    public DashboardService(BudgetClient budgetClient,
                            TransactionClient transactionClient,
                            UserClient userClient) {
        this.budgetClient = budgetClient;
        this.transactionClient = transactionClient;
        this.userClient = userClient;
    }

    /**
     * Joins budget limits with EXPENSE spend per category over the window. When
     * from/to are null the window defaults to the current calendar month. Spend in
     * categories that have no budget is not shown; a budgeted category with no spend
     * shows {@code spentAmount = 0}.
     */
    public BudgetProgressResponse budgetProgress(String authorization, LocalDate fromDate, LocalDate toDate) {
        YearMonth currentMonth = YearMonth.now();
        LocalDate from = fromDate != null ? fromDate : currentMonth.atDay(1);
        LocalDate to = toDate != null ? toDate : currentMonth.atEndOfMonth();

        List<BudgetDto> budgets = budgetClient.listBudgets(authorization);
        List<CategorySummaryDto> summaries = transactionClient.categorySummary(authorization, from, to);

        return new BudgetProgressResponse(from, to, buildBudgetItems(budgets, summaries));
    }

    /** Per-budget limit vs EXPENSE spend join (compute only; data already fetched). */
    private List<BudgetProgressItem> buildBudgetItems(List<BudgetDto> budgets, List<CategorySummaryDto> summaries) {
        Map<Long, BigDecimal> expenseByCategory = expenseByCategory(summaries);
        return budgets.stream()
                .map(b -> toProgressItem(b, expenseByCategory.getOrDefault(b.categoryId(), BigDecimal.ZERO)))
                .toList();
    }

    private BudgetProgressItem toProgressItem(BudgetDto b, BigDecimal spent) {
        BigDecimal limit = b.limitAmount() != null ? b.limitAmount() : BigDecimal.ZERO;
        BigDecimal remaining = limit.subtract(spent);
        BigDecimal percentUsed = limit.signum() > 0
                ? spent.multiply(HUNDRED).divide(limit, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new BudgetProgressItem(
                b.id(), b.name(), b.categoryId(), b.periodType(), b.startDate(), b.endDate(),
                b.currency(), limit, spent, remaining, percentUsed);
    }

    /**
     * Headline KPIs for the window (defaults to the current month). Income/expense/balance
     * are computed over ALL categories; budget utilization is spend in budgeted categories
     * over the sum of budget limits. Reuses the same two upstream calls as budget-progress.
     */
    public DashboardSummaryResponse summary(String authorization, LocalDate fromDate, LocalDate toDate) {
        YearMonth currentMonth = YearMonth.now();
        LocalDate from = fromDate != null ? fromDate : currentMonth.atDay(1);
        LocalDate to = toDate != null ? toDate : currentMonth.atEndOfMonth();

        List<BudgetDto> budgets = budgetClient.listBudgets(authorization);
        List<CategorySummaryDto> summaries = transactionClient.categorySummary(authorization, from, to);

        return buildSummary(budgets, summaries, from, to);
    }

    /** Headline KPIs + budget utilization (compute only; data already fetched). */
    private DashboardSummaryResponse buildSummary(List<BudgetDto> budgets, List<CategorySummaryDto> summaries,
                                                  LocalDate from, LocalDate to) {
        BigDecimal totalIncome = sumByType(summaries, "INCOME");
        BigDecimal totalExpense = sumByType(summaries, "EXPENSE");
        BigDecimal remainingBalance = totalIncome.subtract(totalExpense);

        Map<Long, BigDecimal> expenseByCategory = expenseByCategory(summaries);
        BigDecimal totalLimit = budgets.stream()
                .map(b -> b.limitAmount() != null ? b.limitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Spend counted once per budgeted category, even if several budgets share a category.
        Set<Long> budgetedCategories = budgets.stream()
                .map(BudgetDto::categoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        BigDecimal totalSpent = budgetedCategories.stream()
                .map(c -> expenseByCategory.getOrDefault(c, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal utilizationPercent = totalLimit.signum() > 0
                ? totalSpent.multiply(HUNDRED).divide(totalLimit, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BudgetUtilization utilization = new BudgetUtilization(totalLimit, totalSpent, utilizationPercent);
        return new DashboardSummaryResponse(from, to, totalIncome, totalExpense, remainingBalance, utilization);
    }

    /**
     * Most-recent transactions. {@code limit} is clamped to 1..100 (values outside the
     * range are pulled to the nearest bound) before the upstream call; ordering is
     * guaranteed by transaction-service.
     */
    public List<RecentTransactionResponse> recentTransactions(String authorization, int limit) {
        int clampedLimit = Math.min(100, Math.max(1, limit));
        return buildRecentTransactions(transactionClient.recentTransactions(authorization, clampedLimit));
    }

    /** Map upstream transactions to the recent-transaction view (compute only). */
    private List<RecentTransactionResponse> buildRecentTransactions(List<TransactionDto> txns) {
        return txns.stream().map(this::toRecentTransaction).toList();
    }

    private RecentTransactionResponse toRecentTransaction(TransactionDto t) {
        return new RecentTransactionResponse(
                t.id(), t.type(), t.amount(), t.currency(),
                t.categoryId(), t.description(), t.transactionDate());
    }

    /**
     * Top expense categories for the current month, highest spend first. Reuses the same
     * upstream call and {@link #expenseByCategory} grouping as the other views. A
     * negative {@code limit} is clamped to 0 (empty list); the result may be empty.
     */
    public List<TopCategoryResponse> topCategories(String authorization, int limit) {
        YearMonth currentMonth = YearMonth.now();
        List<CategorySummaryDto> summaries = transactionClient.categorySummary(
                authorization, currentMonth.atDay(1), currentMonth.atEndOfMonth());
        return buildTopCategories(summaries, limit);
    }

    /** Top EXPENSE categories from already-fetched summaries, highest first, top {@code limit}. */
    private List<TopCategoryResponse> buildTopCategories(List<CategorySummaryDto> summaries, int limit) {
        Map<Long, BigDecimal> amountByCategory = expenseByCategory(summaries);
        // categoryId -> name, from the same EXPENSE rows (first name wins on the rare dup).
        Map<Long, String> nameByCategory = summaries.stream()
                .filter(s -> "EXPENSE".equalsIgnoreCase(s.type()) && s.categoryId() != null)
                .collect(Collectors.toMap(
                        CategorySummaryDto::categoryId, CategorySummaryDto::categoryName, (a, b) -> a));

        return amountByCategory.entrySet().stream()
                .map(e -> new TopCategoryResponse(e.getKey(), nameByCategory.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(TopCategoryResponse::amount).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    /** EXPENSE spend per category for the window (income rows and null categories excluded). */
    private Map<Long, BigDecimal> expenseByCategory(List<CategorySummaryDto> summaries) {
        return summaries.stream()
                .filter(s -> "EXPENSE".equalsIgnoreCase(s.type()) && s.categoryId() != null)
                .collect(Collectors.toMap(
                        CategorySummaryDto::categoryId,
                        s -> s.total() != null ? s.total() : BigDecimal.ZERO,
                        BigDecimal::add));
    }

    /** Sum of category totals for one transaction type (INCOME/EXPENSE). */
    private BigDecimal sumByType(List<CategorySummaryDto> summaries, String type) {
        return summaries.stream()
                .filter(s -> type.equalsIgnoreCase(s.type()))
                .map(s -> s.total() != null ? s.total() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Income/expense trend over the window (defaults to the current month). Reuses
     * transaction-service's DAILY {@code summary/trend} via a single upstream call;
     * {@code granularity=month} (default) buckets those daily points into calendar months
     * (summing income/expense, balance = income - expense), {@code granularity=day} passes
     * them through. Output is chronological; any value other than "day" means month.
     */
    public List<TrendPointResponse> trend(String authorization, LocalDate fromDate, LocalDate toDate,
                                          String granularity) {
        YearMonth currentMonth = YearMonth.now();
        LocalDate from = fromDate != null ? fromDate : currentMonth.atDay(1);
        LocalDate to = toDate != null ? toDate : currentMonth.atEndOfMonth();

        List<TrendPointDto> daily = transactionClient.trend(authorization, from, to);
        return bucketTrend(daily, granularity);
    }

    /** Convert daily upstream points into the trend series (compute only; data already fetched). */
    private List<TrendPointResponse> bucketTrend(List<TrendPointDto> daily, String granularity) {
        if ("day".equalsIgnoreCase(granularity)) {
            return daily.stream().map(p -> {
                BigDecimal income = nullToZero(p.income());
                BigDecimal expense = nullToZero(p.expense());
                return new TrendPointResponse(p.date().toString(), income, expense, income.subtract(expense));
            }).toList();
        }

        // Bucket daily points into calendar months. TreeMap keeps the series chronological
        // regardless of the order upstream returns the points in.
        Map<YearMonth, BigDecimal[]> byMonth = new TreeMap<>();
        for (TrendPointDto p : daily) {
            BigDecimal[] incomeExpense = byMonth.computeIfAbsent(
                    YearMonth.from(p.date()), m -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            incomeExpense[0] = incomeExpense[0].add(nullToZero(p.income()));
            incomeExpense[1] = incomeExpense[1].add(nullToZero(p.expense()));
        }

        List<TrendPointResponse> trend = new ArrayList<>(byMonth.size());
        byMonth.forEach((month, ie) ->
                trend.add(new TrendPointResponse(month.toString(), ie[0], ie[1], ie[0].subtract(ie[1]))));
        return trend;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Composite BFF view: profile + all five feature blocks for the window (defaults to the
     * current month). Each upstream dataset is fetched EXACTLY ONCE and shared across blocks
     * (budgets feed summary + budget-overview; the category summary feeds summary + budget-
     * overview + top-categories), so there are no duplicate upstream calls. The five independent
     * fetches run concurrently (PF-1) but still fail-fast — they are joined in fixed order and the
     * first upstream error propagates unchanged as an UpstreamException → 502. {@code limit} bounds
     * both the recent-transactions block (clamped to 1..100 for the upstream fetch) and the
     * top-categories block; {@code profile} is null if none exists.
     */
    public DashboardResponse composite(String authorization, LocalDate fromDate, LocalDate toDate, int limit) {
        YearMonth currentMonth = YearMonth.now();
        LocalDate from = fromDate != null ? fromDate : currentMonth.atDay(1);
        LocalDate to = toDate != null ? toDate : currentMonth.atEndOfMonth();
        int clampedLimit = Math.min(100, Math.max(1, limit));

        // Fetch-once: five independent upstream datasets, fetched concurrently.
        CompletableFuture<List<BudgetDto>> budgetsF = async(() -> budgetClient.listBudgets(authorization));
        CompletableFuture<List<CategorySummaryDto>> summariesF =
                async(() -> transactionClient.categorySummary(authorization, from, to));
        CompletableFuture<List<TrendPointDto>> dailyF = async(() -> transactionClient.trend(authorization, from, to));
        CompletableFuture<List<TransactionDto>> recentF =
                async(() -> transactionClient.recentTransactions(authorization, clampedLimit));
        CompletableFuture<UserProfileDto> profileF = async(() -> userClient.me(authorization));

        // Join in the original sequential order so the same upstream error surfaces first (fail-fast).
        List<BudgetDto> budgets = join(budgetsF);
        List<CategorySummaryDto> summaries = join(summariesF);
        List<TrendPointDto> daily = join(dailyF);
        List<TransactionDto> recent = join(recentF);
        UserProfileDto profile = join(profileF);

        // Assemble from already-fetched data — no further upstream calls.
        return new DashboardResponse(
                profile,
                buildSummary(budgets, summaries, from, to),
                buildBudgetItems(budgets, summaries),
                buildTopCategories(summaries, limit),
                buildRecentTransactions(recent),
                bucketTrend(daily, "month"));
    }

    /** Landing view: profile + current-month summary + budgets (the three fetches run concurrently). */
    public OverviewResponse overview(String authorization) {
        YearMonth now = YearMonth.now();
        CompletableFuture<UserProfileDto> profileF = async(() -> userClient.me(authorization));
        CompletableFuture<MonthlySummaryDto> currentMonthF =
                async(() -> transactionClient.monthly(authorization, now.getYear(), now.getMonthValue()));
        CompletableFuture<List<BudgetDto>> budgetsF = async(() -> budgetClient.listBudgets(authorization));
        return new OverviewResponse(join(profileF), join(currentMonthF), join(budgetsF));
    }

    /**
     * Runs a blocking upstream call on the common pool, carrying the current request's MDC
     * (the correlation id the RestClient interceptor relays) onto the worker thread so the
     * propagated id is unchanged from the previous single-threaded behaviour.
     */
    private static <T> CompletableFuture<T> async(Supplier<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (context != null) {
                MDC.setContextMap(context);
            } else {
                MDC.clear();
            }
            try {
                return task.get();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        });
    }

    /**
     * Joins a fetch, fail-fast: unwraps {@link CompletionException} so the original upstream
     * exception (e.g. UpstreamException → 502) propagates exactly as it did when calls were
     * sequential. Joining in a fixed order makes the surfaced error deterministic.
     */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
