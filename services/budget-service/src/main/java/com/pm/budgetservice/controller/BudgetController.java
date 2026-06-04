package com.pm.budgetservice.controller;

import com.pm.budgetservice.dto.ApiResponse;
import com.pm.budgetservice.dto.BudgetFilterRequest;
import com.pm.budgetservice.dto.BudgetResponse;
import com.pm.budgetservice.dto.CreateBudgetRequest;
import com.pm.budgetservice.dto.PageMeta;
import com.pm.budgetservice.dto.UpdateBudgetRequest;
import com.pm.budgetservice.security.JwtUserPrincipal;
import com.pm.budgetservice.service.BudgetService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Thin controller: resolves the authenticated userId, delegates to the service,
 * and wraps results in the standard response envelope. No business logic here.
 */
@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BudgetResponse>> create(
            @Valid @RequestBody CreateBudgetRequest request,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        BudgetResponse created = budgetService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BudgetResponse>>> list(
            @Valid @ModelAttribute BudgetFilterRequest filter,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        Page<BudgetResponse> page = budgetService.list(userId, filter);
        PageMeta meta = new PageMeta(filter.getPage(), filter.getLimit(), page.getTotalElements());
        return ResponseEntity.ok(ApiResponse.of(page.getContent(), meta));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponse>> getById(
            @PathVariable UUID id,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.of(budgetService.getById(userId, id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<BudgetResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBudgetRequest request,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.of(budgetService.update(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        budgetService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    private Long extractUserId(Authentication authentication) {
        JwtUserPrincipal principal = (JwtUserPrincipal) authentication.getPrincipal();
        return principal.getUserId();
    }
}
