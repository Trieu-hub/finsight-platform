package com.pm.analyticsservice.summarizer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Deterministic, dependency-free summary. Always available, used by tests (no network)
 * and as the safe fallback for {@link LlmSummarizer}. It states the same figures the
 * read APIs return, in one short paragraph.
 */
@Component
public class TemplateSummarizer implements Summarizer {

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    @Override
    public FinancialSummary summarize(MonthlySummaryData d) {
        String month = monthLabel(d.yearMonth());

        boolean noActivity = d.income().signum() == 0 && d.expense().signum() == 0;
        if (noActivity) {
            return new FinancialSummary("No transactions were recorded for " + month + ".", false);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("In ").append(month).append(" you earned ")
                .append(money(d.income(), d.currency()))
                .append(" and spent ").append(money(d.expense(), d.currency()))
                .append(", a savings rate of ").append(pct(d.savingsRate())).append(".");

        // Compare to the prior month only when there was prior activity worth comparing.
        if (d.prevSavingsRate() != 0.0) {
            double delta = d.savingsRate() - d.prevSavingsRate();
            String direction = delta >= 0 ? "up" : "down";
            sb.append(" That is ").append(direction).append(" from ")
                    .append(pct(d.prevSavingsRate())).append(" the month before.");
        }

        if (!d.topExpenseCategories().isEmpty()) {
            CategoryFigure top = d.topExpenseCategories().get(0);
            sb.append(" Your largest expense was ").append(top.name())
                    .append(" (").append(pct(top.share())).append(" of spending).");
        }

        return new FinancialSummary(sb.toString(), false);
    }

    static String monthLabel(String yearMonth) {
        return YearMonth.parse(yearMonth).format(MONTH_LABEL);
    }

    private static String pct(double value) {
        return String.format(Locale.ENGLISH, "%.1f%%", value);
    }

    private static String money(BigDecimal amount, String currency) {
        // VND has no minor unit; everything else shows two decimals.
        String pattern = "VND".equalsIgnoreCase(currency) ? "%,.0f" : "%,.2f";
        return String.format(Locale.ENGLISH, pattern, amount) + " " + currency;
    }
}
