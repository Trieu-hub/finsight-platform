package com.pm.analyticsservice.forecast;

import com.pm.analyticsservice.entity.DailyCategoryRollup;
import com.pm.analyticsservice.entity.SpendingModel;
import com.pm.analyticsservice.repository.DailyCategoryRollupRepository;
import com.pm.analyticsservice.repository.SpendingModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trainer's wiring: that a fit is scored on withheld days and the score is persisted
 * beside it, and that fitting a model is not the same decision as serving it.
 */
class SpendingModelTrainerTest {

    private static final Long USER = 7L;
    private static final String USD = "USD";
    private static final String EXPENSE = "EXPENSE";
    private static final LocalDate TRAINED_UPTO = LocalDate.of(2026, 3, 31);
    private static final LocalDate FROM = TRAINED_UPTO.minusDays(SpendingModelTrainer.WINDOW_DAYS - 1L);

    private DailyCategoryRollupRepository dailyRepository;
    private SpendingModelRepository modelRepository;
    private SpendingModelTrainer trainer;

    @BeforeEach
    void setUp() {
        dailyRepository = mock(DailyCategoryRollupRepository.class);
        modelRepository = mock(SpendingModelRepository.class);
        trainer = new SpendingModelTrainer(dailyRepository, modelRepository);

        when(dailyRepository.findTrainableSeries(EXPENSE, FROM, TRAINED_UPTO))
                // Explicit type argument: List.of on an Object[] would otherwise be read as a
                // list of its elements rather than a one-row result set.
                .thenReturn(List.<Object[]>of(new Object[]{USER, USD}));
        when(modelRepository.findByUserIdAndCurrency(USER, USD)).thenReturn(Optional.empty());
        when(modelRepository.save(any(SpendingModel.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("persists the holdout score beside the fit it belongs to")
    void writesTheAccuracyItMeasured() {
        givenDailySpend(date -> isWeekend(date) ? 200.0 : 100.0);

        assertThat(trainer.trainAll(TRAINED_UPTO)).isEqualTo(1);

        SpendingModel saved = savedModel();
        assertThat(saved.getHoldoutDays()).isEqualTo(HoldoutBacktest.HOLDOUT_DAYS);
        assertThat(saved.getModelMae()).isLessThan(saved.getBaselineMae());
        assertThat(saved.beatsRunRate()).isTrue();
        // And the fit itself is the one the score describes: weekends learned as dearer.
        assertThat(saved.getDowSat()).isGreaterThan(saved.getDowMon());
    }

    @Test
    @DisplayName("still writes a model that lost, and marks it unfit to serve")
    void keepsFittingSeparateFromServing() {
        givenDailySpend(date -> 80.0);

        // Written — the row is the trainer's output and is worth inspecting either way.
        assertThat(trainer.trainAll(TRAINED_UPTO)).isEqualTo(1);

        SpendingModel saved = savedModel();
        assertThat(saved.getModelMae()).isNotNull();
        // Not served: a flat series is answered exactly by the run rate, so the model adds
        // nothing and the forecast will skip it.
        assertThat(saved.beatsRunRate()).isFalse();
    }

    private SpendingModel savedModel() {
        ArgumentCaptor<SpendingModel> captor = ArgumentCaptor.forClass(SpendingModel.class);
        verify(modelRepository).save(captor.capture());
        return captor.getValue();
    }

    /** Stubs the whole window as one row per day, the shape given by {@code amount}. */
    private void givenDailySpend(Function<LocalDate, Double> amount) {
        List<DailyCategoryRollup> rows = new ArrayList<>();
        for (LocalDate date = FROM; !date.isAfter(TRAINED_UPTO); date = date.plusDays(1)) {
            rows.add(DailyCategoryRollup.builder()
                    .id(UUID.randomUUID())
                    .userId(USER)
                    .spendDate(date)
                    .categoryId(4L)
                    .type(EXPENSE)
                    .currency(USD)
                    .totalAmount(BigDecimal.valueOf(amount.apply(date)))
                    .txnCount(1)
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        when(dailyRepository.findByUserIdAndTypeAndCurrencyAndSpendDateBetweenOrderBySpendDateAsc(
                USER, EXPENSE, USD, FROM, TRAINED_UPTO)).thenReturn(rows);
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
