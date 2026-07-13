package com.pm.transactionservice.game;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * American roulette: the wheel, the legal chip positions and the payout table.
 *
 * <p>The maths is documented in {@code docs/games/american-roulette.md}. The one identity that
 * governs everything: a bet covering {@code n} of the 38 pockets pays {@code (36 - n)/n} to 1,
 * while a fair payout would be {@code (38 - n)/n}. The shortfall of {@code 2/n} gives every bet
 * the same expected value of {@code -2/38 = -5.263%} — except BASKET, which pays 6:1 instead of
 * 6.6:1 and so runs at {@code -3/38 = -7.895%}.
 *
 * <p><b>The bet type is derived from the covered pockets, never taken from the client.</b> Each
 * legal chip position maps to exactly one set of pockets, so the set alone identifies the bet —
 * which means a client cannot claim a 35:1 payout on eighteen numbers. A set that is not in
 * {@link #LEGAL} is not a chip position that exists on a real table and is rejected.
 */
public final class Roulette {

    /** Physical clockwise pocket order of the double-zero wheel. */
    public static final List<String> WHEEL = List.of(
            "0", "28", "9", "26", "30", "11", "7", "20", "32", "17", "5", "22", "34", "15", "3",
            "24", "36", "13", "1",
            "00", "27", "10", "25", "29", "12", "8", "19", "31", "18", "6", "21", "33", "16", "4",
            "23", "35", "14", "2");

    public static final int POCKETS = 38;

    private static final List<Integer> RED_NUMBERS = List.of(
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    /** Bet types and their payout in "x to 1". */
    public enum BetType {
        STRAIGHT(35), SPLIT(17), STREET(11), TRIO(11), CORNER(8),
        BASKET(6), SIXLINE(5), COLUMN(2), DOZEN(2),
        RED(1), BLACK(1), ODD(1), EVEN(1), LOW(1), HIGH(1);

        private final int payout;

        BetType(int payout) {
            this.payout = payout;
        }

        /** Winnings per unit staked, on top of the returned stake. */
        public int payout() {
            return payout;
        }
    }

    /**
     * Rank of each pocket, so a set of pockets has one canonical key regardless of order.
     *
     * <p>Declared before {@link #LEGAL}: static initialisers run in source order, and building the
     * legal-position table calls {@link #keyOf}, which needs this map already populated.
     */
    private static final Map<String, Integer> RANK = buildRanks();

    /** Every legal chip position on the layout: 149 inside + 12 outside = 161 propositions. */
    private static final Map<String, BetType> LEGAL = buildLegal();

    private static final SecureRandom RANDOM = new SecureRandom();

    private Roulette() {
    }

    public static String colourOf(String pocket) {
        if ("0".equals(pocket) || "00".equals(pocket)) {
            return "green";
        }
        return RED_NUMBERS.contains(Integer.parseInt(pocket)) ? "red" : "black";
    }

    /**
     * Draws a pocket uniformly at random.
     *
     * <p>{@link SecureRandom} rather than {@link java.util.Random} (whose 48-bit LCG state is
     * recoverable from two outputs, making future spins predictable), and {@code nextInt(bound)},
     * which rejection-samples internally — so there is no modulo bias across the 38 pockets.
     */
    public static String spin() {
        return WHEEL.get(RANDOM.nextInt(POCKETS));
    }

    /**
     * The bet a set of pockets represents, or {@code null} if those pockets do not touch on the
     * layout in a way that a chip can actually be placed on.
     */
    public static BetType classify(List<String> pockets) {
        if (pockets == null || pockets.isEmpty()) {
            return null;
        }
        return LEGAL.get(keyOf(pockets));
    }

    /** Canonical, order-independent key for a set of pockets. Rejects duplicates and unknowns. */
    public static String keyOf(List<String> pockets) {
        List<String> sorted = new ArrayList<>(pockets);
        for (String p : sorted) {
            if (!RANK.containsKey(p)) {
                return "invalid";
            }
        }
        if (sorted.stream().distinct().count() != sorted.size()) {
            return "invalid";
        }
        sorted.sort(Comparator.comparingInt(RANK::get));
        return String.join(",", sorted);
    }

    /** Stake plus winnings returned to the player; {@link BigDecimal#ZERO} when the bet lost. */
    public static BigDecimal payoutFor(BetType type, List<String> pockets, BigDecimal amount,
                                       String result) {
        if (!pockets.contains(result)) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(BigDecimal.valueOf(type.payout() + 1L));
    }

    /**
     * Builds the legal-position table from the layout's geometry rather than hard-coding 161
     * entries. On the felt the numbers run in 12 rows of three, so {@code n} and {@code n+1} share
     * a street (unless {@code n} is a multiple of 3, which ends one) and {@code n} and {@code n+3}
     * sit side by side.
     */
    private static Map<String, Integer> buildRanks() {
        Map<String, Integer> ranks = new HashMap<>();
        ranks.put("0", 0);
        ranks.put("00", 1);
        for (int n = 1; n <= 36; n++) {
            ranks.put(String.valueOf(n), n + 1);
        }
        return Map.copyOf(ranks);
    }

    private static Map<String, BetType> buildLegal() {
        Map<String, BetType> m = new LinkedHashMap<>();

        // 38 straights
        m.put(keyOf(List.of("0")), BetType.STRAIGHT);
        m.put(keyOf(List.of("00")), BetType.STRAIGHT);
        for (int n = 1; n <= 36; n++) {
            m.put(keyOf(List.of(s(n))), BetType.STRAIGHT);
        }

        // 62 splits: 24 within a street, 33 between streets, 5 involving a green
        for (int n = 1; n <= 36; n++) {
            if (n % 3 != 0) {
                m.put(keyOf(List.of(s(n), s(n + 1))), BetType.SPLIT);
            }
        }
        for (int n = 1; n <= 33; n++) {
            m.put(keyOf(List.of(s(n), s(n + 3))), BetType.SPLIT);
        }
        m.put(keyOf(List.of("0", "00")), BetType.SPLIT);
        m.put(keyOf(List.of("0", "1")), BetType.SPLIT);
        m.put(keyOf(List.of("0", "2")), BetType.SPLIT);
        m.put(keyOf(List.of("00", "2")), BetType.SPLIT);
        m.put(keyOf(List.of("00", "3")), BetType.SPLIT);

        // 12 streets
        for (int n = 1; n <= 34; n += 3) {
            m.put(keyOf(List.of(s(n), s(n + 1), s(n + 2))), BetType.STREET);
        }

        // 3 trios
        m.put(keyOf(List.of("0", "1", "2")), BetType.TRIO);
        m.put(keyOf(List.of("0", "00", "2")), BetType.TRIO);
        m.put(keyOf(List.of("00", "2", "3")), BetType.TRIO);

        // 22 corners
        for (int n = 1; n <= 32; n++) {
            if (n % 3 != 0) {
                m.put(keyOf(List.of(s(n), s(n + 1), s(n + 3), s(n + 4))), BetType.CORNER);
            }
        }

        // 11 six lines
        for (int n = 1; n <= 31; n += 3) {
            m.put(keyOf(List.of(s(n), s(n + 1), s(n + 2), s(n + 3), s(n + 4), s(n + 5))),
                    BetType.SIXLINE);
        }

        // 1 basket (the only 7.89% bet on the table)
        m.put(keyOf(List.of("0", "00", "1", "2", "3")), BetType.BASKET);

        // 12 outside bets
        for (int c = 1; c <= 3; c++) {
            final int col = c % 3;
            m.put(keyOf(numbers(n -> n % 3 == col)), BetType.COLUMN);
        }
        for (int d = 0; d < 3; d++) {
            int lo = d * 12 + 1;
            int hi = (d + 1) * 12;
            m.put(keyOf(numbers(n -> n >= lo && n <= hi)), BetType.DOZEN);
        }
        m.put(keyOf(numbers(n -> "red".equals(colourOf(s(n))))), BetType.RED);
        m.put(keyOf(numbers(n -> "black".equals(colourOf(s(n))))), BetType.BLACK);
        m.put(keyOf(numbers(n -> n % 2 == 1)), BetType.ODD);
        m.put(keyOf(numbers(n -> n % 2 == 0)), BetType.EVEN);
        m.put(keyOf(numbers(n -> n <= 18)), BetType.LOW);
        m.put(keyOf(numbers(n -> n >= 19)), BetType.HIGH);

        return Map.copyOf(m);
    }

    private static List<String> numbers(java.util.function.IntPredicate keep) {
        List<String> out = new ArrayList<>();
        for (int n = 1; n <= 36; n++) {
            if (keep.test(n)) {
                out.add(s(n));
            }
        }
        return out;
    }

    private static String s(int n) {
        return String.valueOf(n);
    }
}
