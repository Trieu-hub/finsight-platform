package com.pm.notificationservice.narrator;

import com.pm.notificationservice.event.RiskDetectedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule-based narrator must produce a non-empty title/message for every known risk
 * type and fall back gracefully for an unknown one — all deterministically.
 */
class TemplateNarratorTest {

    private final TemplateNarrator narrator = new TemplateNarrator();

    @Test
    void mapsKnownRiskTypeToSpecificWording() {
        AlertContent content = narrator.narrate(event("HIGH_AMOUNT_EXPENSE"));

        assertThat(content.type()).isEqualTo(TemplateNarrator.TYPE_RISK_ALERT);
        assertThat(content.title()).isEqualTo("Large expense detected");
        assertThat(content.message()).isNotBlank();
    }

    /**
     * Every rule risk-service can emit must have wording of its own. A new rule that reaches a
     * user as the generic "Risk alert" is worse than useless: it tells them something happened
     * and not what, and the only symptom is a vague notification nobody reports.
     */
    @Test
    void everyRiskTypeTheRuleEngineEmitsHasItsOwnWording() {
        String[] riskTypes = {
                "HIGH_AMOUNT_EXPENSE", "RAPID_SPENDING", "LARGE_DAILY_SPEND",
                "HIGH_AMOUNT_INCOME", "RAPID_INCOME", "LARGE_DAILY_INCOME", "INCOME_SPIKE",
                "RECURRING_CHARGE_DETECTED", "RECURRING_PRICE_INCREASE", "RECURRING_CHARGE_MISSED"};

        for (String riskType : riskTypes) {
            AlertContent content = narrator.narrate(event(riskType));
            assertThat(content.title())
                    .as("wording for %s", riskType)
                    .isNotBlank()
                    .isNotEqualTo("Risk alert");
            assertThat(content.message()).as("message for %s", riskType).isNotBlank();
        }
    }

    @Test
    void namesWhatChangedAboutARecurringCharge() {
        assertThat(narrator.narrate(event("RECURRING_CHARGE_DETECTED")).title())
                .isEqualTo("New recurring charge");
        assertThat(narrator.narrate(event("RECURRING_PRICE_INCREASE")).title())
                .isEqualTo("A recurring charge went up");
        assertThat(narrator.narrate(event("RECURRING_CHARGE_MISSED")).title())
                .isEqualTo("A recurring charge didn't arrive");
    }

    @Test
    void fallsBackForUnknownRiskType() {
        AlertContent content = narrator.narrate(event("SOMETHING_NEW"));

        assertThat(content.type()).isEqualTo(TemplateNarrator.TYPE_RISK_ALERT);
        assertThat(content.title()).isEqualTo("Risk alert");
        assertThat(content.message()).isNotBlank();
    }

    @Test
    void handlesNullRiskTypeWithoutThrowing() {
        AlertContent content = narrator.narrate(event(null));

        assertThat(content.title()).isEqualTo("Risk alert");
    }

    private RiskDetectedEvent event(String riskType) {
        return new RiskDetectedEvent(UUID.randomUUID(), "RiskDetected", "2026-06-26T10:00:00Z",
                42L, UUID.randomUUID(), riskType, "HIGH");
    }
}
