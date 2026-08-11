package com.pm.analyticsservice.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trainer is pure arithmetic, so these run without Docker or Spring.
 *
 * <p>Each test builds a series whose shape is known by construction and then asserts the fit
 * recovers that shape — a model that merely "produces numbers" would pass a smoke test and
 * fail every one of these.
 */
class SeasonalTrendTrainerTest {

    private static final int MONDAY = 0;
    private static final int SATURDAY = 5;
    private static final int SUNDAY = 6;

    /** Eight weeks starting on a Monday: 100 on weekdays, 200 at weekends, no trend. */
    private static double[] weekendHeavy(int weeks) {
        double[] daily = new double[weeks * 7];
        for (int i = 0; i < daily.length; i++) {
            int dow = i % 7;
            daily[i] = (dow == SATURDAY || dow == SUNDAY) ? 200.0 : 100.0;
        }
        return daily;
    }

    @Test
    @DisplayName("learns that weekends cost twice a weekday")
    void recoversWeeklyShape() {
        SeasonalTrendModel model = SeasonalTrendTrainer.fit(weekendHeavy(8), MONDAY);

        assertThat(model).isNotNull();
        double[] index = model.dowIndex();

        // Normalised to mean 1: five weekdays at x and two weekend days at 2x average to 1,
        // so x = 7/9 ≈ 0.778 and the weekend sits at ≈ 1.556 — a ratio of exactly 2.
        assertThat(index[SATURDAY] / index[MONDAY]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.05));
        assertThat(index[SUNDAY] / index[MONDAY]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.05));
        assertThat(java.util.Arrays.stream(index).average().orElseThrow())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("predicts a Saturday higher than the Monday beside it")
    void predictionCarriesTheWeeklyShape() {
        SeasonalTrendModel model = SeasonalTrendTrainer.fit(weekendHeavy(8), MONDAY);

        double monday = model.predict(1, MONDAY);
        double saturday = model.predict(1, SATURDAY);

        assertThat(saturday).isGreaterThan(monday);
        // This is the claim the run-rate forecast cannot make: it would predict the same
        // number for both days, because it only knows the average.
        assertThat(saturday / monday).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    @DisplayName("recovers a rising trend and projects it forward")
    void recoversTrend() {
        // Six weeks climbing by 2 per day, no weekly shape.
        double[] daily = new double[42];
        for (int i = 0; i < daily.length; i++) {
            daily[i] = 50.0 + 2.0 * i;
        }

        SeasonalTrendModel model = SeasonalTrendTrainer.fit(daily, MONDAY);

        assertThat(model).isNotNull();
        assertThat(model.trend()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.4));
        // Ten days past the window the series would sit at 50 + 2*51 = 152.
        assertThat(model.predict(10, MONDAY)).isCloseTo(152.0, org.assertj.core.data.Offset.offset(8.0));
    }

    @Test
    @DisplayName("a flat series has no weekly shape to find")
    void flatSeriesGivesNeutralIndices() {
        double[] daily = new double[35];
        java.util.Arrays.fill(daily, 80.0);

        SeasonalTrendModel model = SeasonalTrendTrainer.fit(daily, MONDAY);

        assertThat(model).isNotNull();
        for (double index : model.dowIndex()) {
            assertThat(index).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        }
        assertThat(model.sigma()).isLessThan(1.0);
    }

    @Test
    @DisplayName("refuses a window shorter than two weeks instead of guessing")
    void refusesShortSeries() {
        double[] daily = new double[SeasonalTrendTrainer.MIN_DAYS - 1];
        java.util.Arrays.fill(daily, 100.0);

        assertThat(SeasonalTrendTrainer.fit(daily, MONDAY)).isNull();
    }

    @Test
    @DisplayName("refuses a series with no spend at all")
    void refusesAllZeroSeries() {
        assertThat(SeasonalTrendTrainer.fit(new double[30], MONDAY)).isNull();
    }

    @Test
    @DisplayName("never predicts negative spend, however steep the decline")
    void predictionIsFlooredAtZero() {
        // Falls by 5 a day from 100 — extrapolating far enough would go below zero.
        double[] daily = new double[28];
        for (int i = 0; i < daily.length; i++) {
            daily[i] = Math.max(0.0, 100.0 - 5.0 * i);
        }

        SeasonalTrendModel model = SeasonalTrendTrainer.fit(daily, MONDAY);

        assertThat(model).isNotNull();
        assertThat(model.predict(365, MONDAY)).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("with no evidence of their own, a user gets the population's weekly shape")
    void shrinkageFallsBackToThePopulation() {
        double[] userIndex = {2.0, 0.5, 0.5, 0.5, 0.5, 2.0, 1.0};
        double[] population = {0.8, 0.8, 0.8, 0.8, 1.0, 1.4, 1.4};

        double[] blended = SeasonalTrendTrainer.shrinkTowards(userIndex, population, new int[7]);

        assertThat(blended).containsExactly(population, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("with plenty of evidence, a user keeps their own weekly shape")
    void shrinkageYieldsToTheUserAsEvidenceGrows() {
        // Chosen to already average 1.0: the blend re-normalises, so a vector with any other
        // mean would come back rescaled and the comparison below would be against the wrong
        // numbers rather than against the user's shape.
        double[] userIndex = {1.4, 0.85, 0.85, 0.85, 0.85, 1.1, 1.1};
        double[] population = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
        int[] plenty = new int[7];
        java.util.Arrays.fill(plenty, 200);

        double[] blended = SeasonalTrendTrainer.shrinkTowards(userIndex, population, plenty);

        for (int d = 0; d < 7; d++) {
            assertThat(blended[d]).isCloseTo(userIndex[d], org.assertj.core.data.Offset.offset(0.02));
        }
    }

    @Test
    @DisplayName("blending lands between the two, weighted by how much the user has shown")
    void shrinkageIsProportionalToEvidence() {
        double[] userIndex = {2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
        double[] population = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
        // Four observations equals the prior's weight, so Monday should land midway at 1.5
        // before normalisation pulls the whole vector back to mean 1.
        int[] observations = {4, 4, 4, 4, 4, 4, 4};

        double[] blended = SeasonalTrendTrainer.shrinkTowards(userIndex, population, observations);

        assertThat(blended[0]).isGreaterThan(blended[1]);
        assertThat(blended[0] / blended[1]).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("counts how often each weekday appears in a training window")
    void countsWeekdayObservations() {
        int[] counts = SeasonalTrendTrainer.weekdayObservations(30, MONDAY);

        assertThat(java.util.Arrays.stream(counts).sum()).isEqualTo(30);
        // 30 days from a Monday: Mon/Tue covered five times, the rest four.
        assertThat(counts[MONDAY]).isEqualTo(5);
        assertThat(counts[SUNDAY]).isEqualTo(4);
    }
}
