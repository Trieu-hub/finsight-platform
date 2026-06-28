package com.pm.analyticsservice.controller;

import com.pm.analyticsservice.dto.ApiResponse;
import com.pm.analyticsservice.dto.CategorySliceResponse;
import com.pm.analyticsservice.dto.ForecastResponse;
import com.pm.analyticsservice.dto.MonthlySummaryResponse;
import com.pm.analyticsservice.dto.OverviewResponse;
import com.pm.analyticsservice.exception.InvalidAnalyticsRequestException;
import com.pm.analyticsservice.security.JwtUserPrincipal;
import com.pm.analyticsservice.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.util.List;

/**
 * Analytics read API. Every figure is scoped to the authenticated user's id taken from
 * the JWT (never from the request). {@code year}/{@code month} default to the current
 * month; {@code currency} is optional (the user's dominant currency is used when absent).
 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<OverviewResponse>> overview(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String currency,
            Authentication authentication) {
        Long userId = userId(authentication);
        YearMonth ym = resolveMonth(year, month);
        return ResponseEntity.ok(ApiResponse.of(
                analyticsService.overview(userId, ym.getYear(), ym.getMonthValue(), currency)));
    }

    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<CategorySliceResponse>>> categories(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String currency,
            Authentication authentication) {
        Long userId = userId(authentication);
        YearMonth current = YearMonth.now();
        String fromKey = parseMonth(from, current);
        String toKey = parseMonth(to, current);
        if (fromKey.compareTo(toKey) > 0) {
            throw new InvalidAnalyticsRequestException("'from' must not be after 'to'");
        }
        return ResponseEntity.ok(ApiResponse.of(
                analyticsService.categories(userId, fromKey, toKey, currency)));
    }

    @GetMapping("/forecast")
    public ResponseEntity<ApiResponse<ForecastResponse>> forecast(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String currency,
            Authentication authentication) {
        Long userId = userId(authentication);
        YearMonth ym = resolveMonth(year, month);
        return ResponseEntity.ok(ApiResponse.of(
                analyticsService.forecast(userId, ym.getYear(), ym.getMonthValue(), currency)));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<MonthlySummaryResponse>> summary(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String currency,
            Authentication authentication) {
        Long userId = userId(authentication);
        YearMonth ym = resolveMonth(year, month);
        return ResponseEntity.ok(ApiResponse.of(
                analyticsService.summary(userId, ym.getYear(), ym.getMonthValue(), currency)));
    }

    private YearMonth resolveMonth(Integer year, Integer month) {
        YearMonth now = YearMonth.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        try {
            return YearMonth.of(y, m);
        } catch (DateTimeException e) {
            throw new InvalidAnalyticsRequestException("year/month is invalid");
        }
    }

    /** Parse a {@code YYYY-MM} string, defaulting to the given month when blank. */
    private String parseMonth(String value, YearMonth fallback) {
        if (value == null || value.isBlank()) {
            return fallback.toString();
        }
        try {
            return YearMonth.parse(value).toString();
        } catch (DateTimeException e) {
            throw new InvalidAnalyticsRequestException("month must be in YYYY-MM format: " + value);
        }
    }

    private Long userId(Authentication authentication) {
        JwtUserPrincipal principal = (JwtUserPrincipal) authentication.getPrincipal();
        return principal.getUserId();
    }
}
