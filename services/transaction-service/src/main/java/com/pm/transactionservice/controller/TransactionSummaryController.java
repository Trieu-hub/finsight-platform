package com.pm.transactionservice.controller;

import com.pm.transactionservice.dto.ApiResponse;
import com.pm.transactionservice.dto.CategorySummaryResponse;
import com.pm.transactionservice.dto.MonthlySummaryResponse;
import com.pm.transactionservice.dto.TrendPointResponse;
import com.pm.transactionservice.exception.InvalidTransactionDataException;
import com.pm.transactionservice.security.JwtUserPrincipal;
import com.pm.transactionservice.service.SummaryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Analytics endpoints. Added as a separate controller so the existing
 * /api/v1/transactions endpoints are untouched. Every figure is scoped to the
 * authenticated user's id taken from the JWT.
 */
@RestController
@RequestMapping("/api/v1/transactions/summary")
public class TransactionSummaryController {

    private static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final SummaryService summaryService;

    public TransactionSummaryController(SummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/monthly")
    public ResponseEntity<ApiResponse<MonthlySummaryResponse>> monthly(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        YearMonth ym = resolveMonth(year, month);
        return ResponseEntity.ok(ApiResponse.of(
                summaryService.monthly(userId, ym.getYear(), ym.getMonthValue())));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategorySummaryResponse>>> categories(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        // Categories default to all-time when no range is supplied.
        LocalDate from = fromDate != null ? fromDate : MIN_DATE;
        LocalDate to = toDate != null ? toDate : MAX_DATE;
        return ResponseEntity.ok(ApiResponse.of(summaryService.byCategory(userId, from, to)));
    }

    @GetMapping("/trend")
    public ResponseEntity<ApiResponse<List<TrendPointResponse>>> trend(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        // Trend defaults to the current month.
        YearMonth current = YearMonth.now();
        LocalDate from = fromDate != null ? fromDate : current.atDay(1);
        LocalDate to = toDate != null ? toDate : current.atEndOfMonth();
        return ResponseEntity.ok(ApiResponse.of(summaryService.trend(userId, from, to)));
    }

    private YearMonth resolveMonth(Integer year, Integer month) {
        YearMonth now = YearMonth.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        try {
            return YearMonth.of(y, m);
        } catch (DateTimeException e) {
            throw new InvalidTransactionDataException("year/month is invalid");
        }
    }

    private Long extractUserId(Authentication authentication) {
        JwtUserPrincipal principal = (JwtUserPrincipal) authentication.getPrincipal();
        return principal.getUserId();
    }
}
