package com.pm.authservice.service;

import com.pm.authservice.audit.AuditLog;
import com.pm.authservice.dto.admin.AdminUserResponse;
import com.pm.authservice.entity.Role;
import com.pm.authservice.entity.User;
import com.pm.authservice.enums.RoleName;
import com.pm.authservice.exception.ResourceNotFoundException;
import com.pm.authservice.repository.RoleRepository;
import com.pm.authservice.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read + write administrative operations on accounts. ROLE_ADMIN authorization is
 * enforced in SecurityConfig (the {@code /api/v1/auth/admin/**} matcher), so this layer
 * assumes the caller is an authenticated administrator.
 *
 * <p>Guard rails: an admin may not change the role of, disable, or delete their OWN
 * account (prevents self-lockout). Role/status changes revoke the target's refresh token
 * so the change takes effect on their next login instead of lingering in a stale JWT.
 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditLog auditLog;

    public AdminService(UserRepository userRepository,
                        RoleRepository roleRepository,
                        RefreshTokenService refreshTokenService,
                        AuditLog auditLog) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenService = refreshTokenService;
        this.auditLog = auditLog;
    }

    public List<AdminUserResponse> listUsers() {
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public AdminUserResponse updateRole(Long id, String roleName, String callerEmail) {
        User user = find(id);
        guardNotSelf(user, callerEmail);

        RoleName target = parseRole(roleName);
        Role role = roleRepository.findByName(target)
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + roleName));
        user.setRole(role);
        userRepository.save(user);

        // Force the user to re-authenticate so their next JWT carries the new role.
        refreshTokenService.revokeByUser(id);
        auditLog.record("UPDATE_ROLE", "user", id, callerEmail, target.name());
        return toResponse(user);
    }

    @Transactional
    public AdminUserResponse setEnabled(Long id, boolean enabled, String callerEmail) {
        User user = find(id);
        guardNotSelf(user, callerEmail);

        user.setEnabled(enabled);
        userRepository.save(user);
        if (!enabled) {
            refreshTokenService.revokeByUser(id); // kill the disabled user's active session
        }
        auditLog.record("SET_ENABLED", "user", id, callerEmail, enabled);
        return toResponse(user);
    }

    @Transactional
    public void delete(Long id, String callerEmail) {
        User user = find(id);
        guardNotSelf(user, callerEmail);

        refreshTokenService.revokeByUser(id);
        userRepository.delete(user);
        auditLog.record("DELETE", "user", id, callerEmail, null);
    }

    // --- helpers ---------------------------------------------------------------

    private User find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    private void guardNotSelf(User user, String callerEmail) {
        if (callerEmail != null && callerEmail.equalsIgnoreCase(user.getEmail())) {
            throw new IllegalArgumentException("You cannot modify your own admin account");
        }
    }

    private RoleName parseRole(String roleName) {
        try {
            return RoleName.valueOf(roleName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid role: " + roleName);
        }
    }

    private AdminUserResponse toResponse(User u) {
        return AdminUserResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .email(u.getEmail())
                .role(u.getRole() != null ? u.getRole().getName().name() : null)
                .enabled(Boolean.TRUE.equals(u.getEnabled()))
                .createdAt(u.getCreatedAt())
                .build();
    }
}
