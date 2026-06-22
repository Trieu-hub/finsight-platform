package com.pm.authservice.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateRoleRequest {

    @NotBlank(message = "role is required")
    private String role; // e.g. ROLE_USER, ROLE_ADMIN, ROLE_ANALYST
}
