package com.pm.analyticsservice.summarizer;

/**
 * A summarizer's output: the narrative text and whether the LLM produced it
 * ({@code aiGenerated=false} means the deterministic template answered, either as the
 * default or as the fallback after an LLM error).
 */
public record FinancialSummary(String text, boolean aiGenerated) {
}
