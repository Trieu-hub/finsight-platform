package com.pm.notificationservice.entity;

import java.time.Duration;

/**
 * How often a user wants the content-carrying channels (email, webhook) to fire.
 *
 * <p>This governs batching only, never whether an alert is recorded: the in-app bell and the SSE
 * stream are the record and always fire immediately. Web push is immediate too — it carries no
 * payload, so holding a ping back would delay the nudge without saving the user any reading.
 */
public enum DigestMode {

    /** One alert, one delivery — the behaviour before digests existed, and the default. */
    IMMEDIATE(null),

    HOURLY(Duration.ofHours(1)),

    DAILY(Duration.ofDays(1));

    private final Duration window;

    DigestMode(Duration window) {
        this.window = window;
    }

    /** How long alerts are collected before they go out together. Null for {@link #IMMEDIATE}. */
    public Duration window() {
        return window;
    }

    public boolean isDeferred() {
        return window != null;
    }
}
