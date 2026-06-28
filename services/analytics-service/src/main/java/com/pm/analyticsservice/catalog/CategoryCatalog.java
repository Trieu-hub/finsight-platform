package com.pm.analyticsservice.catalog;

import java.util.Map;

/**
 * Static id → name lookup for the platform's seed categories (transaction-service's
 * {@code V2__seed_categories.sql}). analytics-service only receives a {@code categoryId}
 * on the event, never the name; resolving names locally lets the breakdown and the AI
 * summary read naturally without a runtime call to transaction-service (the platform's
 * services do not call each other synchronously).
 *
 * <p>This mirrors a small, stable seed. An unknown id (or the {@code 0} uncategorized
 * sentinel) degrades to a generic label rather than failing.
 */
public final class CategoryCatalog {

    /** Sentinel category id used when the event carries no categoryId. */
    public static final long UNCATEGORIZED = 0L;

    private static final Map<Long, String> NAMES = Map.ofEntries(
            Map.entry(1L, "Salary"),
            Map.entry(2L, "Investment"),
            Map.entry(3L, "Refund"),
            Map.entry(4L, "Food & Dining"),
            Map.entry(5L, "Transport"),
            Map.entry(6L, "Housing"),
            Map.entry(7L, "Utilities"),
            Map.entry(8L, "Entertainment"),
            Map.entry(9L, "Healthcare"),
            Map.entry(10L, "Other"));

    private CategoryCatalog() {
    }

    public static String name(Long categoryId) {
        if (categoryId == null || categoryId == UNCATEGORIZED) {
            return "Uncategorized";
        }
        return NAMES.getOrDefault(categoryId, "Category " + categoryId);
    }
}
