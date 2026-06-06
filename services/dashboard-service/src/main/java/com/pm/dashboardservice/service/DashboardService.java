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
import com.pm.dashboardservice.dto.BudgetUtilization;
import com.pm.dashboardservice.dto.DashboardSummaryResponse;
import com.pm.dashboardservice.dto.OverviewResponse;
import com.pm.dashboardservice.dto.RecentTransactionResponse;
import com.pm.dashboardservice.dto.TopCategoryResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

        Map<Long, BigDecimal> expenseByCategory = expenseByCategory(summaries);

        List<BudgetProgressItem> items = budgets.stream()
                .map(b -> toProgressItem(b, expenseByCategory.getOrDefault(b.categoryId(), BigDecimal.ZERO)))
                .toList();

        return new BudgetProgressResponse(from, to, items);
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
        return transactionClient.recentTransactions(authorization, clampedLimit).stream()
                .map(this::toRecentTransaction)
                .toList();
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

    /** Landing view: profile + current-month summary + budgets. */
    public OverviewResponse overview(String authorization) {
        UserProfileDto profile = userClient.me(authorization);
        YearMonth now = YearMonth.now();
        MonthlySummaryDto currentMonth = transactionClient.monthly(authorization, now.getYear(), now.getMonthValue());
        List<BudgetDto> budgets = budgetClient.listBudgets(authorization);
        return new OverviewResponse(profile, currentMonth, budgets);
    }
}
