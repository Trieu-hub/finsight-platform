package com.pm.budgetservice.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

@Getter
public class JwtUserPrincipal {

    private final Long userId;
    private final String email;
    private final String role;

    public JwtUserPrincipal(Long userId, String email, String role) {
        this.userId = userId;
        this.email = email;
        this.role = role;
    }

    public Collection<GrantedAuthority> getAuthorities() {
        if (role == null || role.isBlank()) return List.of();
        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return List.of(new SimpleGrantedAuthority(authority));
    }
}
