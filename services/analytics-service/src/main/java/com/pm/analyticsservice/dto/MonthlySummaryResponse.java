package com.pm.analyticsservice.dto;

/**
 * A month's narrative summary. {@code aiGenerated} tells the client whether the LLM
 * produced it (false means the deterministic template did, as default or fallback).
 */
public record MonthlySummaryResponse(
        String yearMonth,
        String currency,
        String summary,
        boolean aiGenerated
) {
}
