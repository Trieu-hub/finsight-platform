package com.pm.notificationservice.narrator;

/**
 * The presentable content of a notification: a short {@code type} tag, a {@code title}
 * and a human-readable {@code message}. Produced by an {@link AlertNarrator} from a
 * structured upstream event.
 */
public record AlertContent(String type, String title, String message) {
}
