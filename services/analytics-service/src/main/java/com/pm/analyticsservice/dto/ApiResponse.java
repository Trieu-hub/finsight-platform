package com.pm.analyticsservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Standard success envelope:
 * { "success": true, "data": {...} }
 * Matches the platform-wide contract used by the other services. analytics-service
 * endpoints are not paginated, so no {@code meta} block is carried.
 */
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data);
    }
}
