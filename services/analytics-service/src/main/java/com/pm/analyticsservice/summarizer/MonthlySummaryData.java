package com.pm.analyticsservice.summarizer;

import java.math.BigDecimal;
import java.util.List;

/**
 * The anonymized aggregate handed to a {@link Summarizer}. Carries only figures and
 * category names for one month — never a userId, email, or individual transaction — so
 * even the LLM path leaks no identity.
 */
public record MonthlySummaryData(
        String yearMonth,
        String currency,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net,
        double savingsRate,
        double prevSavingsRate,
        List<CategoryFigure> topExpenseCategories
) {
}
