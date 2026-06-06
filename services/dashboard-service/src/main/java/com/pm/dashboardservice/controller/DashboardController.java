package com.pm.dashboardservice.controller;

import com.pm.dashboardservice.dto.ApiResponse;
import com.pm.dashboardservice.dto.BudgetProgressResponse;
import com.pm.dashboardservice.dto.DashboardSummaryResponse;
import com.pm.dashboardservice.dto.OverviewResponse;
import com.pm.dashboardservice.dto.RecentTransactionResponse;
import com.pm.dashboardservice.dto.TopCategoryResponse;
import com.pm.dashboardservice.service.DashboardService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only dashboard endpoints. Thin: it relays the validated bearer token to the
 * service (which forwards it to the upstreams) and wraps results in the standard
 * envelope. All routes require authentication (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok(ApiResponse.of(
                dashboardService.summary(authorization, fromDate, toDate)));
    }

    @GetMapping("/budget-progress")
    public ResponseEntity<ApiResponse<BudgetProgressResponse>> budgetProgress(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok(ApiResponse.of(
                dashboardService.budgetProgress(authorization, fromDate, toDate)));
    }

    // Spec name for the per-budget spent-vs-limit view; same data as /budget-progress
    // (kept as a back-compat alias). See docs/DASHBOARD_SERVICE_DESIGN.md.
    @GetMapping("/budget-overview")
    public ResponseEntity<ApiResponse<BudgetProgressResponse>> budgetOverview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok(ApiResponse.of(
                dashboardService.budgetProgress(authorization, fromDate, toDate)));
    }

    @GetMapping("/recent-transactions")
    public ResponseEntity<ApiResponse<List<RecentTransactionResponse>>> recentTransactions(
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.recentTransactions(authorization, limit)));
    }

    @GetMapping("/top-categories")
    public ResponseEntity<ApiResponse<List<TopCategoryResponse>>> topCategories(
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.topCategories(authorization, limit)));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<OverviewResponse>> overview(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok(ApiResponse.of(dashboardService.overview(authorization)));
    }
}
