package com.pm.riskservice.rule;

/**
 * The risk rules and their fixed severity mapping (Phase D.4). The enum constant name is
 * the {@code riskType} carried on the RiskDetected event; {@link #severity()} is the
 * mapped {@code riskSeverity}. Adding a rule is a new constant here plus its predicate in
 * {@link RiskRuleEngine}.
 */
public enum RiskRule {

    /** A single EXPENSE at or above a high amount for that user (see {@code RiskRuleEngine}). */
    HIGH_AMOUNT_EXPENSE("HIGH"),
    /** Many EXPENSE transactions for one user in a short window. */
    RAPID_SPENDING("MEDIUM"),
    /** A user's EXPENSE total for a single day exceeding their own daily bar. */
    LARGE_DAILY_SPEND("HIGH"),

    /** A single INCOME at or above a high amount for that user — unexplained money arriving. */
    HIGH_AMOUNT_INCOME("MEDIUM"),
    /** Many INCOME transactions for one user in a short window. */
    RAPID_INCOME("MEDIUM"),
    /** A user's INCOME total for a single day exceeding their own daily bar. */
    LARGE_DAILY_INCOME("MEDIUM"),
    /**
     * An INCOME far above the user's own historical average — the income-side mirror of
     * UNUSUAL_TRANSACTION_AMOUNT, and the strongest "this doesn't look like you" signal
     * because it is relative to the user rather than to a fixed number.
     */
    INCOME_SPIKE("HIGH");

    private final String severity;

    RiskRule(String severity) {
        this.severity = severity;
    }

    public String severity() {
        return severity;
    }
}
