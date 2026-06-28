package com.pm.analyticsservice.summarizer;

/**
 * Seam for turning a month's aggregate into a human-readable summary. The default
 * {@link TemplateSummarizer} is deterministic and always available; the optional
 * {@link LlmSummarizer} (when enabled) phrases it with an LLM and falls back to the
 * template on any error.
 */
public interface Summarizer {

    FinancialSummary summarize(MonthlySummaryData data);
}
