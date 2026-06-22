package com.pm.authservice.controller;

import com.pm.authservice.dto.admin.AdminUserResponse;
import com.pm.authservice.dto.admin.UpdateRoleRequest;
import com.pm.authservice.dto.admin.UpdateStatusRequest;
import com.pm.authservice.service.AdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin-only console. Access restricted to ROLE_ADMIN in SecurityConfig
 * ({@code /api/v1/auth/admin/**} requires {@code hasRole("ADMIN")}).
 *
 * <p>{@code authentication.getName()} is the caller's email (the UserDetails username),
 * passed to the service so it can block self-modification.
 */
@RestController
@RequestMapping("/api/v1/auth/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> users() {
        return ResponseEntity.ok(adminService.listUsers());
    }

    @PatchMapping("/users/{id}/role")
    public ResponseEntity<AdminUserResponse> updateRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(adminService.updateRole(id, request.getRole(), authentication.getName()));
    }

    @PatchMapping("/users/{id}/status")
    public ResponseEntity<AdminUserResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStatusRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(adminService.setEnabled(id, request.getEnabled(), authentication.getName()));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            Authentication authentication) {
        adminService.delete(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
