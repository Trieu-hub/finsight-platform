package com.pm.notificationservice.narrator;

import com.pm.notificationservice.event.RiskDetectedEvent;
import org.springframework.stereotype.Component;

/**
 * Default rule-based {@link AlertNarrator}: maps a {@code riskType} to a fixed,
 * human-readable message. Deterministic and dependency-free, so it works offline and
 * keeps tests stable. (An LLM-backed narrator can replace this behind a flag later.)
 *
 * <p>The notification {@code type} is always {@code RISK_ALERT} for now — every event
 * this service consumes is a detected risk. The {@code riskType} discriminates the
 * wording within that.
 */
@Component
public class TemplateNarrator implements AlertNarrator {

    public static final String TYPE_RISK_ALERT = "RISK_ALERT";

    @Override
    public AlertContent narrate(RiskDetectedEvent event) {
        String riskType = event.riskType() == null ? "" : event.riskType();
        String title;
        String message;
        switch (riskType) {
            case "HIGH_AMOUNT_EXPENSE" -> {
                title = "Large expense detected";
                message = "An unusually large expense was detected on your account. "
                        + "Review it if you don't recognize the activity.";
            }
            case "RAPID_SPENDING" -> {
                title = "Rapid spending detected";
                message = "Several expenses were recorded in a short window. "
                        + "Check that all of them were made by you.";
            }
            case "LARGE_DAILY_SPEND" -> {
                title = "High spending today";
                message = "Your total spending today is unusually high compared with "
                        + "your normal activity.";
            }
            case "HIGH_AMOUNT_INCOME" -> {
                title = "Large income detected";
                message = "An unusually large payment arrived on your account. "
                        + "Confirm you know where it came from.";
            }
            case "RAPID_INCOME" -> {
                title = "Rapid income detected";
                message = "Several payments were credited in a short window. "
                        + "Check that all of them were expected.";
            }
            case "LARGE_DAILY_INCOME" -> {
                title = "High income today";
                message = "The money credited to your account today is unusually high "
                        + "compared with your normal activity.";
            }
            case "INCOME_SPIKE" -> {
                title = "Income far above your usual";
                message = "This payment is several times larger than your typical income. "
                        + "Review it if you weren't expecting it.";
            }
            case "RECURRING_CHARGE_DETECTED" -> {
                title = "New recurring charge";
                message = "A charge in this category has repeated on a regular schedule, "
                        + "so it now counts towards your fixed monthly spending.";
            }
            case "RECURRING_PRICE_INCREASE" -> {
                title = "A recurring charge went up";
                message = "One of your regular charges cost noticeably more than usual "
                        + "this time. Check whether the price has changed for good.";
            }
            case "RECURRING_CHARGE_MISSED" -> {
                title = "A recurring charge didn't arrive";
                message = "A charge that normally repeats by now has not been recorded. "
                        + "If you didn't cancel it, the payment may have failed.";
            }
            default -> {
                title = "Risk alert";
                message = "A potential risk was detected on your account. "
                        + "Please review your recent activity.";
            }
        }
        return new AlertContent(TYPE_RISK_ALERT, title, message);
    }
}
