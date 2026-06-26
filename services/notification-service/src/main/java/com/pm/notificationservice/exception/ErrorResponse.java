package com.pm.notificationservice.exception;

import lombok.Getter;

/**
 * Standard error envelope:
 * { "success": false, "error": { "code": "...", "message": "..." } }
 */
@Getter
public class ErrorResponse {

    private final boolean success = false;
    private final ApiError error;

    public ErrorResponse(String code, String message) {
        this.error = new ApiError(code, message);
    }
}
