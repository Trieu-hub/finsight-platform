package com.pm.authservice.exception;

import lombok.Getter;

/**
 * Standard platform error envelope, shared in shape with transaction- and
 * budget-service: { "success": false, "error": { "code": "...", "message": "..." } }.
 */
@Getter
public class ErrorResponse {

    private final boolean success = false;
    private final ApiError error;

    public ErrorResponse(String code, String message) {
        this.error = new ApiError(code, message);
    }
}
