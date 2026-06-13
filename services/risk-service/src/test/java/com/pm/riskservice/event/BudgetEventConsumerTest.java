package com.pm.riskservice.event;

import com.pm.riskservice.entity.BudgetSnapshot;
import com.pm.riskservice.repository.BudgetSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the budget read-model consumer: a {@code BudgetChanged} event is mapped to a
 * {@link BudgetSnapshot} upsert keyed by the budget id; an event missing its id/dates is dropped.
 */
class BudgetEventConsumerTest {

    private final BudgetSnapshotRepository repository = mock(BudgetSnapshotRepository.class);
    private final BudgetEventConsumer consumer = new BudgetEventConsumer(repository);

    @Test
    void upsertsSnapshotKeyedByBudgetId() {
        UUID budgetId = UUID.randomUUID();
        consumer.onBudgetChanged(new BudgetChangedEvent(
                UUID.randomUUID(), "BudgetChanged", "2026-06-01T00:00:00Z",
                budgetId, 42L, 7L, "USD", new BigDecimal("1000"),
                "2026-06-01", "2026-06-30", "MONTHLY", false));

        ArgumentCaptor<BudgetSnapshot> captor = ArgumentCaptor.forClass(BudgetSnapshot.class);
        verify(repository).save(captor.capture());
        BudgetSnapshot saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(budgetId);
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getCategoryId()).isEqualTo(7L);
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getLimitAmount()).isEqualByComparingTo("1000");
        assertThat(saved.getStartDate()).isEqualTo(LocalDate.parse("2026-06-01"));
        assertThat(saved.getEndDate()).isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(saved.isDeleted()).isFalse();
    }

    @Test
    void carriesTheDeletedFlagThrough() {
        consumer.onBudgetChanged(new BudgetChangedEvent(
                UUID.randomUUID(), "BudgetChanged", "2026-06-01T00:00:00Z",
                UUID.randomUUID(), 42L, 7L, "USD", new BigDecimal("1000"),
                "2026-06-01", "2026-06-30", "MONTHLY", true));

        ArgumentCaptor<BudgetSnapshot> captor = ArgumentCaptor.forClass(BudgetSnapshot.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isDeleted()).isTrue();
    }

    @Test
    void dropsEventWithUnparseableDates() {
        consumer.onBudgetChanged(new BudgetChangedEvent(
                UUID.randomUUID(), "BudgetChanged", "2026-06-01T00:00:00Z",
                UUID.randomUUID(), 42L, 7L, "USD", new BigDecimal("1000"),
                "not-a-date", "2026-06-30", "MONTHLY", false));

        verify(repository, never()).save(any());
    }
}
