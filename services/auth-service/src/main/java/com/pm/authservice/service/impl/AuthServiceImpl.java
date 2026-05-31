package com.pm.authservice.service.impl;

import com.pm.authservice.dto.auth.AuthResponse;
import com.pm.authservice.dto.auth.LoginRequest;
import com.pm.authservice.dto.auth.RefreshTokenRequest;
import com.pm.authservice.dto.auth.RegisterRequest;
import com.pm.authservice.entity.RefreshToken;
import com.pm.authservice.entity.Role;
import com.pm.authservice.entity.User;
import com.pm.authservice.enums.RoleName;
import com.pm.authservice.exception.DuplicateResourceException;
import com.pm.authservice.exception.InvalidCredentialsException;
import com.pm.authservice.exception.ResourceNotFoundException;
import com.pm.authservice.repository.RefreshTokenRepository;
import com.pm.authservice.repository.RoleRepository;
import com.pm.authservice.repository.UserRepository;
import com.pm.authservice.security.jwt.JwtProperties;
import com.pm.authservice.security.jwt.JwtService;
import com.pm.authservice.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthServiceImpl(UserRepository userRepository,
                           RoleRepository roleRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
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
        RefreshToken refreshToken = issueRefreshToken(user);

        return new AuthResponse(true, "Login successful", accessToken, refreshToken.getToken());
    }

    @Override
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken existing = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired refresh token"));

        if (existing.getExpiryDate().isBefore(LocalDateTime.now())) {
            refreshTokenRepository.delete(existing);
            throw new InvalidCredentialsException("Invalid or expired refresh token");
        }

        User user = existing.getUser();

        refreshTokenRepository.delete(existing);

        String accessToken = jwtService.generateAccessToken(user);
        RefreshToken newRefreshToken = issueRefreshToken(user);

        return new AuthResponse(true, "Token refreshed", accessToken, newRefreshToken.getToken());
    }

    @Override
    @Transactional
    public AuthResponse logout(RefreshTokenRequest request) {
        refreshTokenRepository.findByToken(request.getRefreshToken())
                .ifPresent(token -> refreshTokenRepository.deleteByUser(token.getUser()));

        return new AuthResponse(true, "Logged out successfully");
    }

    private RefreshToken issueRefreshToken(User user) {
        refreshTokenRepository.deleteByUser(user);

        RefreshToken refreshToken = RefreshToken.builder()
                .token(UUID.randomUUID().toString())
                .user(user)
                .expiryDate(LocalDateTime.now().plus(
                        jwtProperties.getRefreshTokenExpiration(), ChronoUnit.MILLIS))
                .build();

        return refreshTokenRepository.save(refreshToken);
    }
}
