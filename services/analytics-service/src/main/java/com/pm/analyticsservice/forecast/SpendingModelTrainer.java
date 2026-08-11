package com.pm.analyticsservice.forecast;

import com.pm.analyticsservice.entity.DailyCategoryRollup;
import com.pm.analyticsservice.entity.SpendingModel;
import com.pm.analyticsservice.repository.DailyCategoryRollupRepository;
import com.pm.analyticsservice.repository.SpendingModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fits and persists one {@link SpendingModel} per (user, currency) from the daily rollup.
 *
 * <p>Two passes, and the order matters. The first pass fits the <b>population</b> series —
 * every user's spend summed per day — to get a weekly shape backed by everyone's data at
 * once. The second pass fits each user, then blends their weekday indices toward that
 * population shape in proportion to how much evidence they personally have
 * ({@link SeasonalTrendTrainer#shrinkTowards}).
 *
 * <p>That ordering is what makes the model usable on a young account. Fitting each user in
 * isolation would give a three-week-old account seven indices estimated from three
 * observations each — numbers that look personalised and are mostly noise. Pooling starts
 * them on the crowd's Saturday and hands the account its own as it earns it.
 */
@Service
public class SpendingModelTrainer {

    private static final Logger log = LoggerFactory.getLogger(SpendingModelTrainer.class);

    private static final String EXPENSE = "EXPENSE";

    /** Training window. Long enough for a stable weekly shape, short enough to still track. */
    public static final int WINDOW_DAYS = 120;

    private final DailyCategoryRollupRepository dailyRepository;
    private final SpendingModelRepository modelRepository;

    public SpendingModelTrainer(DailyCategoryRollupRepository dailyRepository,
                                SpendingModelRepository modelRepository) {
        this.dailyRepository = dailyRepository;
        this.modelRepository = modelRepository;
    }

    /**
     * Retrains every series with enough history in the window ending {@code trainedUpto}
     * (inclusive).
     *
     * @return how many models were written
     */
    @Transactional
    public int trainAll(LocalDate trainedUpto) {
        LocalDate from = trainedUpto.minusDays(WINDOW_DAYS - 1L);
        List<Object[]> series = dailyRepository.findTrainableSeries(EXPENSE, from, trainedUpto);
        if (series.isEmpty()) {
            log.info("Spending model training: no daily spend in {}..{}, nothing to fit", from, trainedUpto);
            return 0;
        }

        double[] population = populationIndices(series, from, trainedUpto);
        int firstDow = dayOfWeekIndex(from);
        LocalDateTime now = LocalDateTime.now();
        int written = 0;

        for (Object[] row : series) {
            Long userId = (Long) row[0];
            String currency = (String) row[1];

            double[] daily = dailySeries(userId, currency, from, trainedUpto);
            SeasonalTrendModel fitted = SeasonalTrendTrainer.fit(daily, firstDow);
            if (fitted == null) {
                // Too short or all zero — leave any previous model alone and let the
                // forecast fall back to the run rate rather than storing a guess.
                continue;
            }

            double[] blended = SeasonalTrendTrainer.shrinkTowards(
                    fitted.dowIndex(), population,
                    SeasonalTrendTrainer.weekdayObservations(daily.length, firstDow));
            SeasonalTrendModel pooled = new SeasonalTrendModel(
                    fitted.level(), fitted.trend(), blended, fitted.sigma(), fitted.sampleDays());

            persist(userId, currency, pooled, trainedUpto, now);
            written++;
        }

        log.info("Spending model training: fitted {} of {} series over {}..{}",
                written, series.size(), from, trainedUpto);
        return written;
    }

    /**
     * The crowd's weekly shape: everyone's daily spend summed into one series and fitted once.
     * Falls back to a flat week when there is not yet enough data platform-wide, which makes
     * the blend a no-op rather than dragging every user toward a noisy prior.
     */
    private double[] populationIndices(List<Object[]> series, LocalDate from, LocalDate to) {
        int days = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        double[] pooled = new double[days];
        for (Object[] row : series) {
            double[] daily = dailySeries((Long) row[0], (String) row[1], from, to);
            for (int i = 0; i < days; i++) {
                pooled[i] += daily[i];
            }
        }

        SeasonalTrendModel model = SeasonalTrendTrainer.fit(pooled, dayOfWeekIndex(from));
        if (model == null) {
            double[] flat = new double[SeasonalTrendModel.WEEK];
            java.util.Arrays.fill(flat, 1.0);
            return flat;
        }
        return model.dowIndex();
    }

    /**
     * One user's expense per day over the window, as a dense array with an explicit 0.0 for
     * every day they spent nothing. The density is load-bearing: a gap would slide every
     * later day onto the wrong weekday and scramble the seasonality.
     */
    private double[] dailySeries(Long userId, String currency, LocalDate from, LocalDate to) {
        List<DailyCategoryRollup> rows = dailyRepository
                .findByUserIdAndTypeAndCurrencyAndSpendDateBetweenOrderBySpendDateAsc(
                        userId, EXPENSE, currency, from, to);

        Map<LocalDate, Double> byDate = new HashMap<>();
        for (DailyCategoryRollup row : rows) {
            byDate.merge(row.getSpendDate(), row.getTotalAmount().doubleValue(), Double::sum);
        }

        int days = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        double[] daily = new double[days];
        for (int i = 0; i < days; i++) {
            daily[i] = byDate.getOrDefault(from.plusDays(i), 0.0);
        }
        return daily;
    }

    private void persist(Long userId, String currency, SeasonalTrendModel model,
                         LocalDate trainedUpto, LocalDateTime now) {
        Optional<SpendingModel> existing = modelRepository.findByUserIdAndCurrency(userId, currency);
        SpendingModel row = existing.orElseGet(() -> SpendingModel.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .currency(currency)
                .build());
        row.apply(model, trainedUpto, now);
        modelRepository.save(row);
    }

    /** 0 == Monday, matching {@link SeasonalTrendModel}'s index convention. */
    static int dayOfWeekIndex(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }
}
