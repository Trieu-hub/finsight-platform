package com.pm.dashboardservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code { "success": ..., "data": ... }} envelope that transaction-service and
 * budget-service return. (user-service returns its DTO raw — no envelope.) Unknown
 * fields such as {@code meta} are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpstreamApiResponse<T>(boolean success, T data) {
}
