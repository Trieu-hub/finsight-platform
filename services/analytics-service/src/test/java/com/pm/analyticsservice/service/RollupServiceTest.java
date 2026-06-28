package com.pm.analyticsservice.service;

import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.event.TransactionCreatedEvent;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RollupServiceTest {

    private MonthlyCategoryRollupRepository rollupRepository;
    private ProcessedEventRepository processedEventRepository;
    private RollupService service;

    @BeforeEach
    void setUp() {
        rollupRepository = mock(MonthlyCategoryRollupRepository.class);
        processedEventRepository = mock(ProcessedEventRepository.class);
        service = new RollupService(rollupRepository, processedEventRepository);
    }

    @Test
    void createsNewRollupForFirstEventInSlot() {
        TransactionCreatedEvent event = event(UUID.randomUUID(), "EXPENSE", "250.00", 4L);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(rollupRepository.findByUserIdAndYearMonthAndCategoryIdAndTypeAndCurrency(
                42L, "2026-06", 4L, "EXPENSE", "USD")).thenReturn(Optional.empty());

        boolean applied = service.apply(event);

        assertThat(applied).isTrue();
        ArgumentCaptor<MonthlyCategoryRollup> saved = ArgumentCaptor.forClass(MonthlyCategoryRollup.class);
        verify(rollupRepository).save(saved.capture());
        assertThat(saved.getValue().getTotalAmount()).isEqualByComparingTo("250.00");
        assertThat(saved.getValue().getTxnCount()).isEqualTo(1);
        verify(processedEventRepository).save(any());
    }

    @Test
    void addsToExistingRollupInSlot() {
        TransactionCreatedEvent event = event(UUID.randomUUID(), "EXPENSE", "250.00", 4L);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        MonthlyCategoryRollup existing = MonthlyCategoryRollup.builder()
                .id(UUID.randomUUID()).userId(42L).yearMonth("2026-06").categoryId(4L)
                .type("EXPENSE").currency("USD").totalAmount(new BigDecimal("100.00")).txnCount(1)
                .build();
        when(rollupRepository.findByUserIdAndYearMonthAndCategoryIdAndTypeAndCurrency(
                42L, "2026-06", 4L, "EXPENSE", "USD")).thenReturn(Optional.of(existing));

        boolean applied = service.apply(event);

        assertThat(applied).isTrue();
        assertThat(existing.getTotalAmount()).isEqualByComparingTo("350.00");
        assertThat(existing.getTxnCount()).isEqualTo(2);
    }

    @Test
    void skipsDuplicateEvent() {
        TransactionCreatedEvent event = event(UUID.randomUUID(), "EXPENSE", "250.00", 4L);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        boolean applied = service.apply(event);

        assertThat(applied).isFalse();
        verify(rollupRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void usesUncategorizedSentinelWhenCategoryMissing() {
        TransactionCreatedEvent event = new TransactionCreatedEvent(UUID.randomUUID(),
                "TransactionCreated", "2026-06-26T10:00:00Z", UUID.randomUUID(), 42L,
                "EXPENSE", new BigDecimal("10.00"), "USD", null, "2026-06-15", null);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(rollupRepository.findByUserIdAndYearMonthAndCategoryIdAndTypeAndCurrency(
                42L, "2026-06", 0L, "EXPENSE", "USD")).thenReturn(Optional.empty());

        boolean applied = service.apply(event);

        assertThat(applied).isTrue();
        ArgumentCaptor<MonthlyCategoryRollup> saved = ArgumentCaptor.forClass(MonthlyCategoryRollup.class);
        verify(rollupRepository).save(saved.capture());
        assertThat(saved.getValue().getCategoryId()).isZero();
    }

    private TransactionCreatedEvent event(UUID eventId, String type, String amount, Long categoryId) {
        return new TransactionCreatedEvent(eventId, "TransactionCreated", "2026-06-26T10:00:00Z",
                UUID.randomUUID(), 42L, type, new BigDecimal(amount), "USD", categoryId, "2026-06-15", null);
    }
}
