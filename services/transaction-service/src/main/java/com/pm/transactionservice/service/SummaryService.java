package com.pm.transactionservice.service;

import com.pm.transactionservice.dto.CategorySummaryResponse;
import com.pm.transactionservice.dto.MonthlySummaryResponse;
import com.pm.transactionservice.dto.TrendPointResponse;

import java.time.LocalDate;
import java.util.List;

public interface SummaryService {

    MonthlySummaryResponse monthly(Long userId, int year, int month);

    List<CategorySummaryResponse> byCategory(Long userId, LocalDate from, LocalDate to);

    List<TrendPointResponse> trend(Long userId, LocalDate from, LocalDate to);
}
