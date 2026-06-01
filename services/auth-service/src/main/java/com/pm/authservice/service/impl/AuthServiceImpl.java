package com.pm.authservice.service.impl;

import com.pm.authservice.dto.auth.AuthResponse;
import com.pm.authservice.dto.auth.LoginRequest;
import com.pm.authservice.dto.auth.RefreshTokenRequest;
import com.pm.authservice.dto.auth.RegisterRequest;
import com.pm.authservice.entity.Role;
import com.pm.authservice.entity.User;
import com.pm.authservice.enums.RoleName;
import com.pm.authservice.exception.DuplicateResourceException;
import com.pm.authservice.exception.InvalidCredentialsException;
import com.pm.authservice.exception.ResourceNotFoundException;
import com.pm.authservice.repository.RoleRepository;
import com.pm.authservice.repository.UserRepository;
import com.pm.authservice.security.jwt.JwtService;
import com.pm.authservice.service.AuthService;
import com.pm.authservice.service.RefreshTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserRepository userRepository,
                           RoleRepository roleRepository,
                           RefreshTokenService refreshTokenService,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("Email is already in use");
        }

        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException("Username is already taken");
        }

        Role userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new ResourceNotFoundException("Default role ROLE_USER not found"));

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .enabled(true)
                .role(userRole)
                .createdAt(LocalDateTime.now())
                .build();

        userRepository.save(user);

        return new AuthResponse(true, "Registration successful");
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);

        return new AuthResponse(true, "Login successful", accessToken, refreshToken);
    }

    @Override
    public AuthResponse refresh(RefreshTokenRequest request) {
        Long userId = refreshTokenService.findUserIdByToken(request.getRefreshToken())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired refresh token"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired refresh token"));

        String accessToken = jwtService.generateAccessToken(user);
        String newRefreshToken = refreshTokenService.issue(user);

        return new AuthResponse(true, "Token refreshed", accessToken, newRefreshToken);
    }

    @Override
    public AuthResponse logout(RefreshTokenRequest request) {
        refreshTokenService.findUserIdByToken(request.getRefreshToken())
                .ifPresent(refreshTokenService::revokeByUser);

        return new AuthResponse(true, "Logged out successfully");
    }
}
