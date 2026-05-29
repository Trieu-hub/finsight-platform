package com.pm.authservice.service;

import com.pm.authservice.dto.auth.AuthResponse;
import com.pm.authservice.dto.auth.RegisterRequest;

public interface AuthService {

    AuthResponse register(RegisterRequest request);
}
