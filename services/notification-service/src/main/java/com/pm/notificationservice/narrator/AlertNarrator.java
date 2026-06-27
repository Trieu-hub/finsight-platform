package com.pm.notificationservice.narrator;

import com.pm.notificationservice.event.RiskDetectedEvent;

/**
 * Turns a structured {@link RiskDetectedEvent} into presentable {@link AlertContent}
 * (title + message) for a notification.
 *
 * <p>This is a deliberate seam. The default implementation ({@link TemplateNarrator})
 * is rule-based and deterministic. A future LLM-backed implementation (e.g. a Claude
 * narrator that phrases the alert more naturally) can be dropped in behind a feature
 * flag without touching the consumer or the service — the rule-based one stays the
 * safe fallback, so the service never depends on an external API being reachable.
 */
public interface AlertNarrator {

    AlertContent narrate(RiskDetectedEvent event);
}
