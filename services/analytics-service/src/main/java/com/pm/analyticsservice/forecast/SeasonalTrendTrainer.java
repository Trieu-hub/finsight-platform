package com.pm.analyticsservice.forecast;

import java.util.Arrays;

/**
 * Fits a {@link SeasonalTrendModel} to one daily-spend series.
 *
 * <p>This is the <b>training</b> step the platform did not previously have: the smoothing
 * constants are not hand-picked constants, they are chosen by minimising in-sample squared
 * error over a grid, and the resulting parameters are persisted and reused. Everything here
 * is deterministic — the same series always yields the same model, which is what makes the
 * forecast reviewable and the tests meaningful.
 *
 * <p>Method: multiplicative weekly season on top of Holt's linear trend.
 * <ol>
 *   <li>Estimate seven day-of-week indices as the ratio of each day's spend to the centred
 *       7-day moving average around it, averaged per weekday and normalised to mean 1.</li>
 *   <li>Divide the series by its index to deseasonalise it.</li>
 *   <li>Run Holt smoothing over the deseasonalised series for each (alpha, beta) on the grid
 *       and keep the pair with the lowest SSE against the ORIGINAL (re-seasonalised) series —
 *       the error that matters is the one in real currency, not in deseasonalised space.</li>
 * </ol>
 *
 * <p><b>Cold start is the hard part, not the smoothing.</b> A user with three weeks of history
 * has ~3 observations per weekday, so a per-user Saturday index fitted from them is mostly
 * noise. {@link #shrinkTowards} pools those indices toward a population prior with a weight
 * that decays as the user's own evidence accumulates, so a new user starts on the crowd's
 * weekly shape and drifts onto their own.
 */
public final class SeasonalTrendTrainer {

    /** Below this many days there is not enough signal to fit anything; caller falls back. */
    public static final int MIN_DAYS = 14;

    /** Grid searched for the smoothing constants. Coarse on purpose — see class notes. */
    private static final double[] ALPHA_GRID = {0.05, 0.1, 0.2, 0.3, 0.5};
    private static final double[] BETA_GRID = {0.0, 0.02, 0.05, 0.1, 0.2};

    /**
     * Strength of the population prior, in pseudo-observations per weekday. A user with 4
     * observations for a given weekday lands halfway between their own index and the crowd's.
     */
    private static final double PRIOR_WEIGHT = 4.0;

    private SeasonalTrendTrainer() {
    }

    /**
     * Fits a model to {@code daily}, where {@code daily[0]} is the spend on
     * {@code firstDayOfWeekIndex} (0 == Monday) and each later entry is the next calendar day.
     * Days with no spend must be present as {@code 0.0} — a gap silently shifts every
     * subsequent day onto the wrong weekday.
     *
     * @return the fitted model, or {@code null} when the series is too short or entirely zero
     */
    public static SeasonalTrendModel fit(double[] daily, int firstDayOfWeekIndex) {
        if (daily == null || daily.length < MIN_DAYS) {
            return null;
        }
        double total = 0.0;
        for (double v : daily) {
            total += v;
        }
        if (total <= 0.0) {
            // A series of pure zeros has no level, no trend and no shape to learn.
            return null;
        }

        double[] index = seasonalIndices(daily, firstDayOfWeekIndex);
        double[] deseasonalised = new double[daily.length];
        for (int i = 0; i < daily.length; i++) {
            deseasonalised[i] = daily[i] / index[dowAt(firstDayOfWeekIndex, i)];
        }

        double bestSse = Double.MAX_VALUE;
        double bestLevel = 0.0;
        double bestTrend = 0.0;
        double bestSigma = 0.0;
        for (double alpha : ALPHA_GRID) {
            for (double beta : BETA_GRID) {
                Holt holt = smooth(deseasonalised, alpha, beta);
                double sse = 0.0;
                for (int i = 1; i < daily.length; i++) {
                    double predicted = holt.fitted[i] * index[dowAt(firstDayOfWeekIndex, i)];
                    double residual = daily[i] - predicted;
                    sse += residual * residual;
                }
                if (sse < bestSse) {
                    bestSse = sse;
                    bestLevel = holt.level;
                    bestTrend = holt.trend;
                    // Residual sd over the n-1 one-step-ahead predictions.
                    bestSigma = Math.sqrt(sse / Math.max(1, daily.length - 1));
                }
            }
        }

        return new SeasonalTrendModel(bestLevel, bestTrend, index, bestSigma, daily.length);
    }

    /**
     * Blends a user's own weekday indices toward a population prior. {@code observations[d]}
     * is how many times weekday {@code d} appears in the user's training window — the weight
     * of their own evidence.
     */
    public static double[] shrinkTowards(double[] userIndex, double[] populationIndex, int[] observations) {
        double[] blended = new double[SeasonalTrendModel.WEEK];
        for (int d = 0; d < SeasonalTrendModel.WEEK; d++) {
            double n = Math.max(0, observations[d]);
            blended[d] = (n * userIndex[d] + PRIOR_WEIGHT * populationIndex[d]) / (n + PRIOR_WEIGHT);
        }
        return normalise(blended);
    }

    /**
     * Ratio-to-moving-average weekday indices. Each day is compared with the centred 7-day
     * average around it, so the trend cancels out and what remains is the weekly shape.
     */
    private static double[] seasonalIndices(double[] daily, int firstDayOfWeekIndex) {
        double[] sum = new double[SeasonalTrendModel.WEEK];
        int[] count = new int[SeasonalTrendModel.WEEK];

        int half = SeasonalTrendModel.WEEK / 2;
        for (int i = half; i < daily.length - half; i++) {
            double window = 0.0;
            for (int k = i - half; k <= i + half; k++) {
                window += daily[k];
            }
            double average = window / SeasonalTrendModel.WEEK;
            if (average <= 0.0) {
                continue;
            }
            int d = dowAt(firstDayOfWeekIndex, i);
            sum[d] += daily[i] / average;
            count[d]++;
        }

        double[] index = new double[SeasonalTrendModel.WEEK];
        for (int d = 0; d < SeasonalTrendModel.WEEK; d++) {
            // A weekday the window never covered keeps a neutral 1.0 rather than a zero,
            // which would erase every prediction for that day.
            index[d] = count[d] == 0 ? 1.0 : sum[d] / count[d];
        }
        return normalise(index);
    }

    /** Scales indices so their mean is 1.0, keeping the level in real currency units. */
    private static double[] normalise(double[] index) {
        double mean = Arrays.stream(index).average().orElse(1.0);
        if (mean <= 0.0) {
            Arrays.fill(index, 1.0);
            return index;
        }
        for (int d = 0; d < index.length; d++) {
            index[d] = index[d] / mean;
        }
        return index;
    }

    /** How many observations of each weekday a window of {@code days} starting at {@code first} holds. */
    public static int[] weekdayObservations(int days, int firstDayOfWeekIndex) {
        int[] count = new int[SeasonalTrendModel.WEEK];
        for (int i = 0; i < days; i++) {
            count[dowAt(firstDayOfWeekIndex, i)]++;
        }
        return count;
    }

    private static int dowAt(int firstDayOfWeekIndex, int offset) {
        return Math.floorMod(firstDayOfWeekIndex + offset, SeasonalTrendModel.WEEK);
    }

    /** Holt's linear-trend smoothing over an already-deseasonalised series. */
    private static Holt smooth(double[] series, double alpha, double beta) {
        double level = series[0];
        double trend = series.length > 1 ? series[1] - series[0] : 0.0;
        double[] fitted = new double[series.length];
        fitted[0] = level;

        for (int i = 1; i < series.length; i++) {
            // One-step-ahead prediction BEFORE seeing series[i] — this is what the SSE scores.
            fitted[i] = level + trend;
            double previousLevel = level;
            level = alpha * series[i] + (1 - alpha) * (level + trend);
            trend = beta * (level - previousLevel) + (1 - beta) * trend;
        }
        return new Holt(level, trend, fitted);
    }

    private record Holt(double level, double trend, double[] fitted) {
    }
}
