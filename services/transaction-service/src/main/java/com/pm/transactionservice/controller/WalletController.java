package com.pm.transactionservice.controller;

import com.pm.transactionservice.dto.ApiResponse;
import com.pm.transactionservice.dto.CreateWalletRequest;
import com.pm.transactionservice.dto.UpdateWalletRequest;
import com.pm.transactionservice.dto.WalletResponse;
import com.pm.transactionservice.security.JwtUserPrincipal;
import com.pm.transactionservice.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thin controller for wallet management. Resolves the authenticated userId, delegates to the
 * service, and wraps results in the standard response envelope. Balances are read-only here —
 * they are maintained by transaction writes, never set through this API.
 */
@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WalletResponse>> create(
            @Valid @RequestBody CreateWalletRequest request,
            Authentication authentication) {
        WalletResponse created = walletService.create(extractUserId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<WalletResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.of(walletService.list(extractUserId(authentication))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<WalletResponse>> getById(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.of(walletService.getById(extractUserId(authentication), id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WalletResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWalletRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                ApiResponse.of(walletService.update(extractUserId(authentication), id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication authentication) {
        walletService.delete(extractUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private Long extractUserId(Authentication authentication) {
        JwtUserPrincipal principal = (JwtUserPrincipal) authentication.getPrincipal();
        return principal.getUserId();
    }
}
