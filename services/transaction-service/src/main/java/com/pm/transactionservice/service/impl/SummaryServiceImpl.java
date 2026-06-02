package com.pm.transactionservice.service.impl;

import com.pm.transactionservice.dto.CategorySummaryResponse;
import com.pm.transactionservice.dto.MonthlySummaryResponse;
import com.pm.transactionservice.dto.TrendPointResponse;
import com.pm.transactionservice.enums.TransactionType;
import com.pm.transactionservice.repository.TransactionRepository;
import com.pm.transactionservice.repository.projection.DailyTypeAggregate;
import com.pm.transactionservice.repository.projection.TypeAggregate;
import com.pm.transactionservice.service.SummaryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only analytics. Every figure comes from a single grouped aggregate query
 * (interface projections) — transaction entities are never hydrated.
 */
@Service
public class SummaryServiceImpl implements SummaryService {

    private final TransactionRepository transactionRepository;

    public SummaryServiceImpl(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public MonthlySummaryResponse monthly(Long userId, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;

        for (TypeAggregate row : transactionRepository.sumByType(userId, ym.atDay(1), ym.atEndOfMonth())) {
            BigDecimal total = nullToZero(row.getTotal());
            if (row.getTransactionType() == TransactionType.INCOME) {
                income = total;
            } else {
                expense = total;
            }
        }
        return new MonthlySummaryResponse(income, expense, income.subtract(expense));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategorySummaryResponse> byCategory(Long userId, LocalDate from, LocalDate to) {
        return transactionRepository.sumByCategory(userId, from, to).stream()
                .map(a -> new CategorySummaryResponse(
                        a.getCategoryId(), a.getCategoryName(), a.getTransactionType(),
                        nullToZero(a.getTotal()), a.getEntryCount()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TrendPointResponse> trend(Long userId, LocalDate from, LocalDate to) {
        // Query is ordered by date asc; LinkedHashMap preserves that order while we
        // fold the two per-type rows of each day into a single point.
        Map<LocalDate, BigDecimal[]> byDate = new LinkedHashMap<>();
        for (DailyTypeAggregate row : transactionRepository.sumDailyByType(userId, from, to)) {
            BigDecimal[] incomeExpense = byDate.computeIfAbsent(
                    row.getEntryDate(), d -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal total = nullToZero(row.getTotal());
            if (row.getTransactionType() == TransactionType.INCOME) {
                incomeExpense[0] = incomeExpense[0].add(total);
            } else {
                incomeExpense[1] = incomeExpense[1].add(total);
            }
        }

        List<TrendPointResponse> trend = new ArrayList<>(byDate.size());
        byDate.forEach((date, ie) -> trend.add(
                new TrendPointResponse(date, ie[0], ie[1], ie[0].subtract(ie[1]))));
        return trend;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
