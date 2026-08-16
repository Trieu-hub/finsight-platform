package com.pm.analyticsservice.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Pure arithmetic, so these run without Docker or Spring.
 *
 * <p>The interesting cases are the ones where the model <b>loses</b>. A backtest that only ever
 * confirms the model would be decoration; each test below builds a series whose right answer is
 * known by construction and asserts the score picks it.
 */
class HoldoutBacktestTest {

    private static final int MONDAY = 0;
    private static final int SATURDAY = 5;
    private static final int SUNDAY = 6;

    /** A flat prior, i.e. the crowd contributes no weekly shape — the least helpful case. */
    private static final double[] NO_PRIOR = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};

    private static double[] weekendHeavy(int weeks) {
        double[] daily = new double[weeks * 7];
        for (int i = 0; i < daily.length; i++) {
            int dow = i % 7;
            daily[i] = (dow == SATURDAY || dow == SUNDAY) ? 200.0 : 100.0;
        }
        return daily;
    }

    @Test
    @DisplayName("beats the run rate on a series with a real weekly shape")
    void modelWinsOnSeasonalSpending() {
        BacktestResult result = HoldoutBacktest.evaluate(weekendHeavy(12), MONDAY, NO_PRIOR);

        assertThat(result).isNotNull();
        assertThat(result.holdoutDays()).isEqualTo(HoldoutBacktest.HOLDOUT_DAYS);
        // The run rate predicts the same number every day, so it is wrong by ~29 on a weekday
        // and ~71 at a weekend. The model knows which day is which.
        assertThat(result.baselineMae()).isCloseTo(40.8, offset(2.0));
        assertThat(result.modelMae()).isLessThan(result.baselineMae());
        assertThat(result.modelWins()).isTrue();
    }

    @Test
    @DisplayName("loses when the trend it learned reverses in the holdout")
    void modelLosesWhenTheTrendBreaks() {
        // Six and a half weeks climbing steadily, then spending stops climbing and collapses.
        // Extrapolating the slope is exactly the wrong move, and the flat run rate suffers less.
        double[] daily = new double[60];
        for (int i = 0; i < daily.length - HoldoutBacktest.HOLDOUT_DAYS; i++) {
            daily[i] = 50.0 + 3.0 * i;
        }
        Arrays.fill(daily, daily.length - HoldoutBacktest.HOLDOUT_DAYS, daily.length, 60.0);

        BacktestResult result = HoldoutBacktest.evaluate(daily, MONDAY, NO_PRIOR);

        assertThat(result).isNotNull();
        assertThat(result.modelMae()).isGreaterThan(result.baselineMae());
        // This is the case the gate exists for: a model that fitted its window beautifully and
        // would still have made the forecast worse.
        assertThat(result.modelWins()).isFalse();
    }

    @Test
    @DisplayName("a tie goes to the run rate — a flat spender needs no model")
    void aTieIsNotAWin() {
        double[] daily = new double[60];
        Arrays.fill(daily, 80.0);

        BacktestResult result = HoldoutBacktest.evaluate(daily, MONDAY, NO_PRIOR);

        assertThat(result).isNotNull();
        // Both are exactly right, which is the point: the extra machinery bought nothing, so
        // there is no reason to serve it.
        assertThat(result.modelMae()).isCloseTo(0.0, offset(1e-6));
        assertThat(result.baselineMae()).isCloseTo(0.0, offset(1e-6));
        assertThat(result.modelWins()).isFalse();
    }

    @Test
    @DisplayName("the fit never sees the holdout")
    void trainingStopsAtTheSplit() {
        // Flat at 100 for the training stretch, then a tenfold jump for exactly the holdout.
        // A fit that had peeked would carry that jump in its level and score well here.
        double[] daily = new double[60];
        Arrays.fill(daily, 100.0);
        Arrays.fill(daily, daily.length - HoldoutBacktest.HOLDOUT_DAYS, daily.length, 1000.0);

        BacktestResult result = HoldoutBacktest.evaluate(daily, MONDAY, NO_PRIOR);

        assertThat(result).isNotNull();
        assertThat(result.modelMae()).isGreaterThan(800.0);
        assertThat(result.baselineMae()).isCloseTo(900.0, offset(1.0));
    }

    @Test
    @DisplayName("refuses to score a series too short to withhold two weeks from")
    void refusesWhenThereIsNothingLeftToTrainOn() {
        // One day short of the minimum training window once the holdout is cut off. Filled with
        // real spend, so it is the length being refused and not an all-zero series.
        double[] daily = new double[HoldoutBacktest.HOLDOUT_DAYS + SeasonalTrendTrainer.MIN_DAYS - 1];
        Arrays.fill(daily, 100.0);

        assertThat(HoldoutBacktest.evaluate(daily, MONDAY, NO_PRIOR)).isNull();
    }
}
