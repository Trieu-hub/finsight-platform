package com.pm.transactionservice.controller;

import com.pm.transactionservice.dto.ApiResponse;
import com.pm.transactionservice.dto.CreateTransactionRequest;
import com.pm.transactionservice.dto.PageMeta;
import com.pm.transactionservice.dto.TransactionFilterRequest;
import com.pm.transactionservice.dto.TransactionResponse;
import com.pm.transactionservice.dto.UpdateTransactionRequest;
import com.pm.transactionservice.security.JwtUserPrincipal;
import com.pm.transactionservice.service.TransactionService;
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
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> create(
            @Valid @RequestBody CreateTransactionRequest request,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        TransactionResponse created = transactionService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> list(
            @Valid @ModelAttribute TransactionFilterRequest filter,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        Page<TransactionResponse> page = transactionService.list(userId, filter);
        PageMeta meta = new PageMeta(filter.getPage(), filter.getLimit(), page.getTotalElements());
        return ResponseEntity.ok(ApiResponse.of(page.getContent(), meta));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getById(
            @PathVariable UUID id,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.of(transactionService.getById(userId, id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransactionRequest request,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        return ResponseEntity.ok(ApiResponse.of(transactionService.update(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            Authentication authentication) {
        Long userId = extractUserId(authentication);
        transactionService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    private Long extractUserId(Authentication authentication) {
        JwtUserPrincipal principal = (JwtUserPrincipal) authentication.getPrincipal();
        return principal.getUserId();
    }
}
