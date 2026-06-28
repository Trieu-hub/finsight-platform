package com.pm.analyticsservice.service.impl;

import com.pm.analyticsservice.catalog.CategoryCatalog;
import com.pm.analyticsservice.dto.CategoryMover;
import com.pm.analyticsservice.dto.CategorySliceResponse;
import com.pm.analyticsservice.dto.ForecastResponse;
import com.pm.analyticsservice.dto.MonthlySummaryResponse;
import com.pm.analyticsservice.dto.OverviewResponse;
import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.service.AnalyticsService;
import com.pm.analyticsservice.summarizer.CategoryFigure;
import com.pm.analyticsservice.summarizer.FinancialSummary;
import com.pm.analyticsservice.summarizer.MonthlySummaryData;
import com.pm.analyticsservice.summarizer.Summarizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final String INCOME = "INCOME";
    private static final String EXPENSE = "EXPENSE";
    private static final String DEFAULT_CURRENCY = "USD";
    private static final int TOP_MOVERS = 5;
    private static final int TOP_SUMMARY_CATEGORIES = 3;
    private static final int MONEY_SCALE = 2;

    private final MonthlyCategoryRollupRepository rollupRepository;
    private final Summarizer summarizer;

    public AnalyticsServiceImpl(MonthlyCategoryRollupRepository rollupRepository,
                                Summarizer summarizer) {
        this.rollupRepository = rollupRepository;
        this.summarizer = summarizer;
    }

    @Override
    @Transactional(readOnly = true)
    public OverviewResponse overview(Long userId, int year, int month, String currencyParam) {
        YearMonth ym = YearMonth.of(year, month);
        String thisKey = ym.toString();
        String prevKey = ym.minusMonths(1).toString();

        List<MonthlyCategoryRollup> thisAll = rollupRepository.findByUserIdAndYearMonth(userId, thisKey);
        List<MonthlyCategoryRollup> prevAll = rollupRepository.findByUserIdAndYearMonth(userId, prevKey);
        String currency = resolveCurrency(currencyParam, thisAll, prevAll);

        List<MonthlyCategoryRollup> cur = inCurrency(thisAll, currency);
        List<MonthlyCategoryRollup> prev = inCurrency(prevAll, currency);

        BigDecimal income = sumOfType(cur, INCOME);
        BigDecimal expense = sumOfType(cur, EXPENSE);
        BigDecimal net = income.subtract(expense);
        BigDecimal prevIncome = sumOfType(prev, INCOME);
        BigDecimal prevExpense = sumOfType(prev, EXPENSE);
        BigDecimal prevNet = prevIncome.subtract(prevExpense);

        return new OverviewResponse(
                thisKey, currency,
                income, expense, net, savingsRate(income, net),
                prevIncome, prevExpense, prevNet, savingsRate(prevIncome, prevNet),
                changePct(prevIncome, income), changePct(prevExpense, expense),
                topMovers(cur, prev));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategorySliceResponse> categories(Long userId, String fromYearMonth,
                                                  String toYearMonth, String currencyParam) {
        List<MonthlyCategoryRollup> all =
                rollupRepository.findByUserIdAndYearMonthBetween(userId, fromYearMonth, toYearMonth);
        String currency = resolveCurrency(currencyParam, all, List.of());
        List<MonthlyCategoryRollup> rows = inCurrency(all, currency);

        // Sum amount + count per (category, type).
        Map<String, BigDecimal> totalByType = new LinkedHashMap<>();
        for (MonthlyCategoryRollup r : rows) {
            totalByType.merge(r.getType(), r.getTotalAmount(), BigDecimal::add);
        }
        record Slot(BigDecimal total, int count) {
        }
        Map<List<Object>, Slot> grouped = new LinkedHashMap<>();
        for (MonthlyCategoryRollup r : rows) {
            List<Object> key = List.of(r.getCategoryId(), r.getType());
            Slot existing = grouped.get(key);
            if (existing == null) {
                grouped.put(key, new Slot(r.getTotalAmount(), r.getTxnCount()));
            } else {
                grouped.put(key, new Slot(existing.total().add(r.getTotalAmount()),
                        existing.count() + r.getTxnCount()));
            }
        }

        List<CategorySliceResponse> slices = new ArrayList<>();
        grouped.forEach((key, slot) -> {
            Long categoryId = (Long) key.get(0);
            String type = (String) key.get(1);
            BigDecimal denom = totalByType.getOrDefault(type, BigDecimal.ZERO);
            double share = denom.signum() == 0 ? 0.0
                    : slot.total().divide(denom, 4, RoundingMode.HALF_UP).doubleValue() * 100.0;
            slices.add(new CategorySliceResponse(categoryId, CategoryCatalog.name(categoryId),
                    type, slot.total(), slot.count(), round1(share)));
        });
        slices.sort(Comparator.comparing(CategorySliceResponse::total).reversed());
        return slices;
    }

    @Override
    @Transactional(readOnly = true)
    public ForecastResponse forecast(Long userId, int year, int month, String currencyParam) {
        YearMonth ym = YearMonth.of(year, month);
        List<MonthlyCategoryRollup> all = rollupRepository.findByUserIdAndYearMonth(userId, ym.toString());
        String currency = resolveCurrency(currencyParam, all, List.of());
        List<MonthlyCategoryRollup> rows = inCurrency(all, currency);

        BigDecimal expenseToDate = sumOfType(rows, EXPENSE);
        int daysInMonth = ym.lengthOfMonth();
        int dayOfMonth = elapsedDays(ym, daysInMonth);

        BigDecimal projected;
        BigDecimal dailyAverage;
        if (dayOfMonth <= 0) {
            // Future month: nothing elapsed, nothing to project from.
            projected = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            dailyAverage = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        } else {
            dailyAverage = expenseToDate.divide(BigDecimal.valueOf(dayOfMonth), MONEY_SCALE, RoundingMode.HALF_UP);
            projected = dailyAverage.multiply(BigDecimal.valueOf(daysInMonth))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        return new ForecastResponse(ym.toString(), currency,
                expenseToDate, dayOfMonth, daysInMonth, projected, dailyAverage);
    }

    @Override
    public MonthlySummaryResponse summary(Long userId, int year, int month, String currencyParam) {
        // NOT @Transactional: the summarizer may call an external LLM, and a read tx must
        // not hold a DB connection open across that network hop. The two finds below each
        // run in their own short auto-commit; the summarize() call happens after they close.
        YearMonth ym = YearMonth.of(year, month);
        String thisKey = ym.toString();
        String prevKey = ym.minusMonths(1).toString();

        List<MonthlyCategoryRollup> thisAll = rollupRepository.findByUserIdAndYearMonth(userId, thisKey);
        List<MonthlyCategoryRollup> prevAll = rollupRepository.findByUserIdAndYearMonth(userId, prevKey);
        String currency = resolveCurrency(currencyParam, thisAll, prevAll);

        List<MonthlyCategoryRollup> cur = inCurrency(thisAll, currency);
        List<MonthlyCategoryRollup> prev = inCurrency(prevAll, currency);

        BigDecimal income = sumOfType(cur, INCOME);
        BigDecimal expense = sumOfType(cur, EXPENSE);
        BigDecimal net = income.subtract(expense);
        BigDecimal prevNet = sumOfType(prev, INCOME).subtract(sumOfType(prev, EXPENSE));

        MonthlySummaryData data = new MonthlySummaryData(
                thisKey, currency, income, expense, net,
                savingsRate(income, net), savingsRate(sumOfType(prev, INCOME), prevNet),
                topExpenseFigures(cur, expense));

        FinancialSummary result = summarizer.summarize(data);
        return new MonthlySummaryResponse(thisKey, currency, result.text(), result.aiGenerated());
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** Day-of-month elapsed: full month for the past, today for the current, 0 for the future. */
    private int elapsedDays(YearMonth ym, int daysInMonth) {
        YearMonth now = YearMonth.now();
        if (ym.isBefore(now)) {
            return daysInMonth;
        }
        if (ym.equals(now)) {
            return LocalDate.now().getDayOfMonth();
        }
        return 0;
    }

    private List<CategoryMover> topMovers(List<MonthlyCategoryRollup> cur, List<MonthlyCategoryRollup> prev) {
        Map<Long, BigDecimal> curByCat = expenseByCategory(cur);
        Map<Long, BigDecimal> prevByCat = expenseByCategory(prev);

        Map<Long, Boolean> keys = new LinkedHashMap<>();
        curByCat.keySet().forEach(k -> keys.put(k, true));
        prevByCat.keySet().forEach(k -> keys.put(k, true));

        List<CategoryMover> movers = new ArrayList<>();
        for (Long categoryId : keys.keySet()) {
            BigDecimal amount = curByCat.getOrDefault(categoryId, BigDecimal.ZERO);
            BigDecimal prevAmount = prevByCat.getOrDefault(categoryId, BigDecimal.ZERO);
            movers.add(new CategoryMover(categoryId, CategoryCatalog.name(categoryId),
                    amount, prevAmount, changePct(prevAmount, amount)));
        }
        // Largest absolute movement first.
        movers.sort(Comparator.comparing(
                (CategoryMover m) -> m.amount().subtract(m.prevAmount()).abs()).reversed());
        return movers.size() > TOP_MOVERS ? movers.subList(0, TOP_MOVERS) : movers;
    }

    private List<CategoryFigure> topExpenseFigures(List<MonthlyCategoryRollup> rows, BigDecimal totalExpense) {
        List<CategoryFigure> figures = new ArrayList<>();
        expenseByCategory(rows).forEach((categoryId, amount) -> {
            double share = totalExpense.signum() == 0 ? 0.0
                    : amount.divide(totalExpense, 4, RoundingMode.HALF_UP).doubleValue() * 100.0;
            figures.add(new CategoryFigure(CategoryCatalog.name(categoryId), amount, round1(share)));
        });
        figures.sort(Comparator.comparing(CategoryFigure::amount).reversed());
        return figures.size() > TOP_SUMMARY_CATEGORIES
                ? figures.subList(0, TOP_SUMMARY_CATEGORIES) : figures;
    }

    private Map<Long, BigDecimal> expenseByCategory(List<MonthlyCategoryRollup> rows) {
        Map<Long, BigDecimal> byCat = new LinkedHashMap<>();
        for (MonthlyCategoryRollup r : rows) {
            if (EXPENSE.equals(r.getType())) {
                byCat.merge(r.getCategoryId(), r.getTotalAmount(), BigDecimal::add);
            }
        }
        return byCat;
    }

    private BigDecimal sumOfType(List<MonthlyCategoryRollup> rows, String type) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MonthlyCategoryRollup r : rows) {
            if (type.equals(r.getType())) {
                sum = sum.add(r.getTotalAmount());
            }
        }
        return sum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private List<MonthlyCategoryRollup> inCurrency(List<MonthlyCategoryRollup> rows, String currency) {
        return rows.stream().filter(r -> currency.equals(r.getCurrency())).toList();
    }

    /**
     * The currency to report in: the caller's choice if given, else the one with the most
     * transactions across the supplied rows, else {@value #DEFAULT_CURRENCY}.
     */
    private String resolveCurrency(String param, List<MonthlyCategoryRollup> a,
                                   List<MonthlyCategoryRollup> b) {
        if (param != null && !param.isBlank()) {
            return param.toUpperCase(Locale.ROOT);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        a.forEach(r -> counts.merge(r.getCurrency(), r.getTxnCount(), Integer::sum));
        b.forEach(r -> counts.merge(r.getCurrency(), r.getTxnCount(), Integer::sum));
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(DEFAULT_CURRENCY);
    }

    private static double savingsRate(BigDecimal income, BigDecimal net) {
        if (income.signum() <= 0) {
            return 0.0;
        }
        return round1(net.divide(income, 4, RoundingMode.HALF_UP).doubleValue() * 100.0);
    }

    /** Percentage change from {@code prev} to {@code cur}; null when prev is zero. */
    private static Double changePct(BigDecimal prev, BigDecimal cur) {
        if (prev.signum() == 0) {
            return null;
        }
        return round1(cur.subtract(prev).divide(prev, 4, RoundingMode.HALF_UP).doubleValue() * 100.0);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
