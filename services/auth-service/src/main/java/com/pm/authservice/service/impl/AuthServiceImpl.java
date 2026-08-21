package com.pm.authservice.service.impl;

import com.pm.authservice.dto.auth.AuthResponse;
import com.pm.authservice.dto.auth.LoginRequest;
import com.pm.authservice.dto.auth.RefreshTokenRequest;
import com.pm.authservice.dto.auth.RegisterRequest;
import com.pm.authservice.entity.Role;
import com.pm.authservice.entity.User;
import com.pm.authservice.enums.RoleName;
import com.pm.authservice.exception.AccountLockedException;
import com.pm.authservice.exception.DisabledAccountException;
import com.pm.authservice.exception.DuplicateResourceException;
import com.pm.authservice.exception.InvalidCredentialsException;
import com.pm.authservice.exception.ResourceNotFoundException;
import com.pm.authservice.repository.RoleRepository;
import com.pm.authservice.repository.UserRepository;
import com.pm.authservice.security.jwt.JwtService;
import com.pm.authservice.service.AuthService;
import com.pm.authservice.service.LoginAttemptService;
import com.pm.authservice.service.RefreshTokenService;
import com.pm.authservice.service.TokenRevocationService;
import com.pm.authservice.validation.StrongPasswordValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Sign-in outcomes, one counter per reason.
     *
     * <p>All four are registered up front rather than on first use: a counter that has never
     * fired is simply absent from `/actuator/prometheus`, so a dashboard panel for it reads "no
     * data" — indistinguishable from "the service is down". Starting them at zero makes a quiet
     * day look like a quiet day.
     */
    private final Counter loginSuccess;
    private final Counter loginBadCredentials;
    private final Counter loginLocked;
    private final Counter loginDisabled;

    public AuthServiceImpl(UserRepository userRepository,
                           RoleRepository roleRepository,
                           RefreshTokenService refreshTokenService,
                           TokenRevocationService tokenRevocationService,
                           LoginAttemptService loginAttemptService,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenService = refreshTokenService;
        this.tokenRevocationService = tokenRevocationService;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;

        this.loginSuccess = loginCounter(meterRegistry, "success");
        this.loginBadCredentials = loginCounter(meterRegistry, "bad_credentials");
        this.loginLocked = loginCounter(meterRegistry, "locked");
        this.loginDisabled = loginCounter(meterRegistry, "disabled");
    }

    private static Counter loginCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("finsight.auth.login")
                .description("Sign-in attempts by outcome")
                .tag("outcome", outcome)
                .register(registry);
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

        // Checked here rather than in the annotation because it needs three fields at once, and a
        // field-level constraint only ever sees one. A password containing the username or the
        // email's local part is guessable by anyone who knows the address — and the address is
        // how you sign in, so that is everyone.
        if (StrongPasswordValidator.echoesIdentity(
                request.getPassword(), request.getUsername(), request.getEmail())) {
            throw new IllegalArgumentException(
                    "Password must not contain your username or email address");
        }

        Role userRole = roleRepository.findByName(RoleName.ROLE_USER)
                .orElseThrow(() -> new ResourceNotFoundException("Default role ROLE_USER not found"));

        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .enabled(true)
                .role(userRole)
                .build();

        userRepository.save(user);

        return new AuthResponse(true, "Registration successful");
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail();

        // Short-circuit locked accounts before touching the DB or hashing a password.
        if (loginAttemptService.isLocked(email)) {
            loginLocked.increment();
            throw new AccountLockedException(
                    "Account temporarily locked due to too many failed login attempts");
        }

        // Unknown user and wrong password are treated identically (no user enumeration);
        // both count as a failed attempt toward lockout. The counter keeps that indistinguishable
        // too — splitting it would leak, through a metric, exactly what the response refuses to.
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailure(email);
            loginBadCredentials.increment();
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // Checked only after the password verifies, so a disabled state is never
        // revealed to someone who does not already hold the correct credentials.
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            loginDisabled.increment();
            throw new DisabledAccountException("Account is disabled");
        }

        loginAttemptService.reset(email);

        // Written through the managed entity: this method is @Transactional, so Hibernate's dirty
        // check flushes it on commit and no explicit save is needed.
        user.setLastLoginAt(LocalDateTime.now());

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user);
        loginSuccess.increment();

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
                .ifPresent(userId -> {
                    refreshTokenService.revokeByUser(userId);
                    // Also kill the access token already in the caller's hands; without this,
                    // "logged out" would remain a client-side fiction for its full TTL.
                    tokenRevocationService.revokeAllForUser(userId);
                });

        return new AuthResponse(true, "Logged out successfully");
    }
}
