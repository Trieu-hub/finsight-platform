package com.pm.dashboardservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard success envelope, matching the rest of the platform:
 * { "success": true, "data": {...} }. The dashboard is read-only and non-paginated,
 * so there is no meta field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data);
    }
}
