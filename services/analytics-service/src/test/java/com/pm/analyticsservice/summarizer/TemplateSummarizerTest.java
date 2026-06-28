package com.pm.analyticsservice.summarizer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSummarizerTest {

    private final TemplateSummarizer summarizer = new TemplateSummarizer();

    @Test
    void describesSavingsRateTrendAndTopCategory() {
        MonthlySummaryData data = new MonthlySummaryData(
                "2026-06", "USD",
                new BigDecimal("1000.00"), new BigDecimal("700.00"), new BigDecimal("300.00"),
                30.0, 20.0,
                List.of(new CategoryFigure("Food & Dining", new BigDecimal("400.00"), 57.1)));

        FinancialSummary result = summarizer.summarize(data);

        assertThat(result.aiGenerated()).isFalse();
        assertThat(result.text())
                .contains("June 2026")
                .contains("30.0%")
                .contains("up from 20.0%")
                .contains("Food & Dining");
    }

    @Test
    void reportsNoActivityWhenEmpty() {
        MonthlySummaryData data = new MonthlySummaryData(
                "2026-06", "USD",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0.0, 0.0, List.of());

        FinancialSummary result = summarizer.summarize(data);

        assertThat(result.aiGenerated()).isFalse();
        assertThat(result.text()).contains("No transactions").contains("June 2026");
    }
}
