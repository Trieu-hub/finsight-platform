package com.pm.analyticsservice.forecast;

/**
 * How a fitted model scored against the run rate on days it was never trained on.
 *
 * <p>Both figures are mean absolute error over the same holdout days, in currency units, so
 * they compare directly: {@code modelMae} is the model's, {@code baselineMae} is what the
 * run-rate projection would have produced for those same days.
 *
 * @param holdoutDays  days withheld from training and scored
 * @param modelMae     the model's mean absolute error on them
 * @param baselineMae  the run rate's mean absolute error on them
 */
public record BacktestResult(int holdoutDays, double modelMae, double baselineMae) {

    /**
     * How much better the model has to be before it is worth serving. A model and a run rate
     * that score within a few percent of each other are the same answer with different
     * machinery behind it, and the difference is inside the noise of a two-week holdout — so
     * the tie goes to the simpler projection.
     */
    public static final double REQUIRED_IMPROVEMENT = 0.05;

    /**
     * The gate the forecast reads. Kept static as well as instance-side because the decision
     * is re-made from the persisted columns on every request, long after this record is gone.
     */
    public static boolean modelWins(double modelMae, double baselineMae) {
        return modelMae < baselineMae * (1.0 - REQUIRED_IMPROVEMENT);
    }

    public boolean modelWins() {
        return modelWins(modelMae, baselineMae);
    }
}
