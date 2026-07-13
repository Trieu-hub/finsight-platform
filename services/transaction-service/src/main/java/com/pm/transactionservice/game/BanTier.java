package com.pm.transactionservice.game;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * How long a user is locked out of the games, by how deep in the red they are.
 *
 * <p>Two escalations compose. The <b>debt</b> picks the starting tier; each <b>prior ban</b> then
 * bumps it one step further, so someone who keeps digging the same hole is out for progressively
 * longer rather than serving the same five minutes forever.
 */
public enum BanTier {

    FIVE_MINUTES(Duration.ofMinutes(5)),
    ONE_HOUR(Duration.ofHours(1)),
    SIX_HOURS(Duration.ofHours(6)),
    ONE_DAY(Duration.ofDays(1)),
    ONE_WEEK(Duration.ofDays(7));

    private static final BanTier[] LADDER = values();

    /** Debt ceilings for the first four tiers; anything above the last one starts at ONE_WEEK. */
    private static final BigDecimal[] CEILINGS = {
            new BigDecimal("1000000"),   // ≤ 1M  → 5 minutes
            new BigDecimal("5000000"),   // ≤ 5M  → 1 hour
            new BigDecimal("20000000"),  // ≤ 20M → 6 hours
            new BigDecimal("50000000"),  // ≤ 50M → 1 day
    };

    private final Duration duration;

    BanTier(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    /** The tier for a given debt and lockout history. {@code priorBans} is the user's ban count. */
    public static BanTier of(BigDecimal debt, long priorBans) {
        int index = LADDER.length - 1;
        for (int i = 0; i < CEILINGS.length; i++) {
            if (debt.compareTo(CEILINGS[i]) <= 0) {
                index = i;
                break;
            }
        }
        long escalated = index + Math.max(priorBans, 0);
        return LADDER[(int) Math.min(escalated, LADDER.length - 1L)];
    }
}
