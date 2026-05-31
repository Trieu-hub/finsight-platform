package com.pm.authservice.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    private boolean success;
    private String message;
    private String accessToken;
    private String refreshToken;

    public AuthResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public AuthResponse(boolean success, String message, String accessToken) {
        this.success = success;
        this.message = message;
        this.accessToken = accessToken;
    }

    public AuthResponse(boolean success, String message, String accessToken, String refreshToken) {
        this.success = success;
        this.message = message;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }
}
