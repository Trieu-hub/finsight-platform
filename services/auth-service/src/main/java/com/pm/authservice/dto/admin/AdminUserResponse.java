package com.pm.authservice.dto.admin;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Admin-only view of an account. Deliberately excludes the password hash and any
 * token material — it is a read-only listing for the ADMIN console.
 */
@Getter
@Builder
public class AdminUserResponse {

    private final Long id;
    private final String username;
    private final String email;
    private final String role;
    private final boolean enabled;
    private final LocalDateTime createdAt;
}
