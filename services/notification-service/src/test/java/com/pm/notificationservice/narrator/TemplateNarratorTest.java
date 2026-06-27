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
