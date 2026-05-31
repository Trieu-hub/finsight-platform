package com.pm.authservice.service;

import com.pm.authservice.dto.auth.AuthResponse;
import com.pm.authservice.dto.auth.LoginRequest;
import com.pm.authservice.dto.auth.RefreshTokenRequest;
import com.pm.authservice.dto.auth.RegisterRequest;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    AuthResponse logout(RefreshTokenRequest request);
}
