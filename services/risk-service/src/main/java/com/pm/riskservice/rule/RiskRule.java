package com.pm.riskservice.rule;

/**
 * The risk rules and their fixed severity mapping (Phase D.4). The enum constant name is
 * the {@code riskType} carried on the RiskDetected event; {@link #severity()} is the
 * mapped {@code riskSeverity}. Adding a rule is a new constant here plus its predicate in
 * {@link RiskRuleEngine}.
 */
public enum RiskRule {

    /** A single EXPENSE at or above a high amount. */
    HIGH_AMOUNT_EXPENSE("HIGH"),
    /** Many EXPENSE transactions for one user in a short window. */
    RAPID_SPENDING("MEDIUM"),
    /** A user's EXPENSE total for a single day exceeding a threshold. */
    LARGE_DAILY_SPEND("HIGH");

    private final String severity;

    RiskRule(String severity) {
        this.severity = severity;
    }

    public String severity() {
        return severity;
    }
}
