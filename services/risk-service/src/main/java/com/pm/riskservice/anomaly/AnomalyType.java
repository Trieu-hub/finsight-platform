package com.pm.riskservice.anomaly;

/**
 * The anomalies risk-service detects. The constant name is the {@code anomaly_type} persisted
 * and the {@code type} metric tag.
 *
 * <ul>
 *   <li>{@code UNUSUAL_TRANSACTION_AMOUNT} (Phase F.1) — an EXPENSE whose amount is at least
 *       3× the user's average historical expense amount, once at least 10 prior EXPENSE
 *       transactions exist.</li>
 * </ul>
 */
public enum AnomalyType {
    UNUSUAL_TRANSACTION_AMOUNT
}
