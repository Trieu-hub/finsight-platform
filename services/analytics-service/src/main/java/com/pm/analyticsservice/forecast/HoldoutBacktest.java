package com.pm.analyticsservice.forecast;

import java.util.Arrays;

/**
 * Scores a model on days it was not trained on.
 *
 * <p>This is the piece that turns "a model exists" into "a model is worth serving".
 * {@link SeasonalTrendTrainer} picks its smoothing constants by minimising error <b>in
 * sample</b>, and in-sample error only ever says how well the model memorised the window it
 * was handed — a model that fits noise scores well there and forecasts badly. So the last
 * {@link #HOLDOUT_DAYS} days are cut off, the model is fitted on what remains, and both it and
 * the run rate it would replace are asked to predict those withheld days blind.
 *
 * <p>The comparison is the point. An error figure on its own is unreadable — is a MAE of 40
 * good? — but "40 against the run rate's 95" answers the only question the forecast has to
 * make: which of the two projections to serve.
 *
 * <p>Nothing here is random and no data is shuffled: the holdout is always the most recent
 * stretch, which is also the only split that matches how the forecast is actually used
 * (predict forward from now, never interpolate a gap in the middle).
 */
public final class HoldoutBacktest {

    /**
     * Two whole weeks. Whole weeks matter: a 10-day holdout would score some weekdays twice
     * and others once, so a model that is merely good at Saturdays would look better than it
     * is on a window that happens to contain two of them.
     */
    public static final int HOLDOUT_DAYS = 14;

    /**
     * How far back the baseline averages. The run rate never looks further than the current
     * month, so a baseline averaging the whole 120-day window would be a weaker opponent than
     * the projection actually in production — and beating a strawman is not evidence.
     * Four whole weeks, again so no weekday is over-represented.
     */
    private static final int BASELINE_LOOKBACK = 28;

    private HoldoutBacktest() {
    }

    /**
     * Fits {@code daily} minus its last {@link #HOLDOUT_DAYS} days and scores that fit against
     * the run rate over those days.
     *
     * @param daily            dense daily spend, one entry per calendar day (see
     *                         {@link SeasonalTrendTrainer#fit})
     * @param firstDayOfWeekIndex weekday of {@code daily[0]}, 0 == Monday
     * @param populationIndex  the weekly prior to blend with, itself fitted <b>without</b> the
     *                         holdout — a prior that has seen the answer is leakage
     * @return the two error figures, or {@code null} when the series is too short to withhold
     *         two weeks and still have enough left to fit
     */
    public static BacktestResult evaluate(double[] daily, int firstDayOfWeekIndex, double[] populationIndex) {
        if (daily == null) {
            return null;
        }
        int trainDays = daily.length - HOLDOUT_DAYS;
        if (trainDays < SeasonalTrendTrainer.MIN_DAYS) {
            return null;
        }

        double[] train = Arrays.copyOf(daily, trainDays);
        SeasonalTrendModel fitted = SeasonalTrendTrainer.fit(train, firstDayOfWeekIndex);
        if (fitted == null) {
            return null;
        }

        // Blended exactly the way the served model is, so the score belongs to the model that
        // would actually answer requests, not to a cleaner one that never leaves this method.
        double[] blended = SeasonalTrendTrainer.shrinkTowards(
                fitted.dowIndex(), populationIndex,
                SeasonalTrendTrainer.weekdayObservations(trainDays, firstDayOfWeekIndex));
        SeasonalTrendModel model = new SeasonalTrendModel(
                fitted.level(), fitted.trend(), blended, fitted.sigma(), fitted.sampleDays());

        double baseline = trailingMean(train);
        double modelError = 0.0;
        double baselineError = 0.0;
        for (int horizon = 1; horizon <= HOLDOUT_DAYS; horizon++) {
            int i = trainDays + horizon - 1;
            int dow = Math.floorMod(firstDayOfWeekIndex + i, SeasonalTrendModel.WEEK);
            modelError += Math.abs(daily[i] - model.predict(horizon, dow));
            baselineError += Math.abs(daily[i] - baseline);
        }

        return new BacktestResult(HOLDOUT_DAYS, modelError / HOLDOUT_DAYS, baselineError / HOLDOUT_DAYS);
    }

    /** The run rate's prediction for every holdout day: recent spend per day, flat. */
    private static double trailingMean(double[] train) {
        int lookback = Math.min(BASELINE_LOOKBACK, train.length);
        double sum = 0.0;
        for (int i = train.length - lookback; i < train.length; i++) {
            sum += train[i];
        }
        return sum / lookback;
    }
}
