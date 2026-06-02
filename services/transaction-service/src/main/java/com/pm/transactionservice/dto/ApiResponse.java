package com.pm.transactionservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Standard success envelope:
 * { "success": true, "data": {...}, "meta": {...} }
 * meta is omitted for non-paginated responses.
 */
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final PageMeta meta;

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> of(T data, PageMeta meta) {
        return new ApiResponse<>(true, data, meta);
    }
}
