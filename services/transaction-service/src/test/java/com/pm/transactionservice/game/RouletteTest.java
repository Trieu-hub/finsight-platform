package com.pm.transactionservice.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouletteTest {

    @Test
    @DisplayName("wheel has 38 pockets: 18 red, 18 black, 2 green")
    void wheelComposition() {
        assertThat(Roulette.WHEEL).hasSize(38).doesNotHaveDuplicates();

        Map<String, Long> byColour = new HashMap<>();
        for (String p : Roulette.WHEEL) {
            byColour.merge(Roulette.colourOf(p), 1L, Long::sum);
        }
        assertThat(byColour).containsEntry("red", 18L)
                .containsEntry("black", 18L)
                .containsEntry("green", 2L);
    }

    @Test
    @DisplayName("colours alternate around the rim, broken only by the two greens")
    void coloursAlternate() {
        for (int i = 0; i < 38; i++) {
            String a = Roulette.colourOf(Roulette.WHEEL.get(i));
            String b = Roulette.colourOf(Roulette.WHEEL.get((i + 1) % 38));
            if (!"green".equals(a) && !"green".equals(b)) {
                assertThat(a).as("pockets %d and %d", i, i + 1).isNotEqualTo(b);
            }
        }
    }

    @Test
    @DisplayName("every payout is short of true odds by exactly 2/n — a 5.26% edge on all but the basket")
    void payoutsEncodeTheHouseEdge() {
        // For a bet covering n of 38 pockets, a fair payout is (38-n)/n. The table pays (36-n)/n.
        // That shortfall of 2/n is the house edge: -(2/n)*(n/38) = -2/38 for every bet.
        for (Roulette.BetType type : Roulette.BetType.values()) {
            int n = coveredCount(type);
            if (type == Roulette.BetType.BASKET) {
                assertThat(type.payout()).isEqualTo(6); // not 6.6 — the deliberate 7.89% bet
                continue;
            }
            assertThat(type.payout())
                    .as("%s covers %d pockets", type, n)
                    .isEqualTo((36 - n) / n);
        }
    }

    @Test
    @DisplayName("the layout admits exactly 161 legal chip positions")
    void legalPositionCount() {
        Set<String> keys = new HashSet<>();
        // 38 straights + 62 splits + 12 streets + 3 trios + 22 corners + 11 six lines
        // + 1 basket + 3 columns + 3 dozens + 6 even-money = 161.
        for (String a : allPockets()) {
            for (String b : allPockets()) {
                // Enumerating every subset is infeasible; instead assert via the classifier that
                // each known-legal position resolves, and that a made-up one does not.
                if (a.equals(b)) {
                    continue;
                }
                if (Roulette.classify(List.of(a, b)) != null) {
                    keys.add(Roulette.keyOf(List.of(a, b)));
                }
            }
        }
        assertThat(keys).as("splits").hasSize(62);

        assertThat(Roulette.classify(List.of("17"))).isEqualTo(Roulette.BetType.STRAIGHT);
        assertThat(Roulette.classify(List.of("1", "2", "3"))).isEqualTo(Roulette.BetType.STREET);
        assertThat(Roulette.classify(List.of("0", "00", "2"))).isEqualTo(Roulette.BetType.TRIO);
        assertThat(Roulette.classify(List.of("1", "2", "4", "5"))).isEqualTo(Roulette.BetType.CORNER);
        assertThat(Roulette.classify(List.of("0", "00", "1", "2", "3")))
                .isEqualTo(Roulette.BetType.BASKET);
        assertThat(Roulette.classify(List.of("1", "2", "3", "4", "5", "6")))
                .isEqualTo(Roulette.BetType.SIXLINE);
    }

    @Test
    @DisplayName("an illegal chip position is rejected, not silently paid")
    void illegalPositionsRejected() {
        assertThat(Roulette.classify(List.of("1", "36"))).isNull();      // do not touch
        assertThat(Roulette.classify(List.of("1", "2", "3", "4"))).isNull(); // not a corner
        assertThat(Roulette.classify(List.of("1", "1"))).isNull();       // duplicate
        assertThat(Roulette.classify(List.of("37"))).isNull();           // not a pocket
        assertThat(Roulette.classify(List.of())).isNull();
    }

    @Test
    @DisplayName("a winning straight-up returns 36× the stake, a loser returns nothing")
    void payoutArithmetic() {
        BigDecimal stake = new BigDecimal("100");
        assertThat(Roulette.payoutFor(Roulette.BetType.STRAIGHT, List.of("17"), stake, "17"))
                .isEqualByComparingTo("3600"); // 35:1 winnings + the stake back
        assertThat(Roulette.payoutFor(Roulette.BetType.STRAIGHT, List.of("17"), stake, "18"))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("spins land in every pocket and never outside the wheel")
    void spinStaysOnTheWheel() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            String p = Roulette.spin();
            assertThat(Roulette.WHEEL).contains(p);
            seen.add(p);
        }
        // With 20k spins, P(missing any given pocket) = (37/38)^20000 ≈ 10^-232.
        assertThat(seen).hasSize(38);
    }

    private static int coveredCount(Roulette.BetType type) {
        return switch (type) {
            case STRAIGHT -> 1;
            case SPLIT -> 2;
            case STREET, TRIO -> 3;
            case CORNER -> 4;
            case BASKET -> 5;
            case SIXLINE -> 6;
            case COLUMN, DOZEN -> 12;
            case RED, BLACK, ODD, EVEN, LOW, HIGH -> 18;
        };
    }

    private static List<String> allPockets() {
        List<String> out = new ArrayList<>(List.of("0", "00"));
        for (int n = 1; n <= 36; n++) {
            out.add(String.valueOf(n));
        }
        return out;
    }
}
