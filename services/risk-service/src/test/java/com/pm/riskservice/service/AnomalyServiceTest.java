package com.pm.riskservice.service;

import com.pm.riskservice.anomaly.AnomalyType;
import com.pm.riskservice.entity.Anomaly;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.repository.AnomalyRepository;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the UNUSUAL_TRANSACTION_AMOUNT anomaly, with the repositories mocked so the
 * prior-expense count and average can be set precisely. The triggering expense is assumed
 * already recorded by the risk engine; its baseline is the history strictly before it.
 */
class AnomalyServiceTest {

    private static final long USER = 4242L;
    private static final String CUR = "USD";
    private static final String DETECTED = "finsight.anomalies.detected";

    private ObservedExpenseRepository expenseRepository;
    private AnomalyRepository anomalyRepository;
    private SimpleMeterRegistry meterRegistry;
    private AnomalyService service;

    @BeforeEach
    void setUp() {
        expenseRepository = mock(ObservedExpenseRepository.class);
        anomalyRepository = mock(AnomalyRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new AnomalyService(expenseRepository, anomalyRepository, meterRegistry);
        when(anomalyRepository.existsById(any())).thenReturn(false);
        when(anomalyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void firesWhenAmountIsAtLeastThreeTimesAverageWithEnoughHistory() {
        stubBaseline(10L, "100");

        UUID txId = UUID.randomUUID();
        Optional<Anomaly> result = service.evaluate(expense(txId, "300")); // exactly 3×

        assertThat(result).isPresent();
        Anomaly anomaly = result.get();
        assertThat(anomaly.getAnomalyType())
                .isEqualTo(AnomalyType.UNUSUAL_TRANSACTION_AMOUNT.name());
        assertThat(anomaly.getUserId()).isEqualTo(USER);
        assertThat(anomaly.getTransactionId()).isEqualTo(txId);
        assertThat(anomaly.getAmount()).isEqualByComparingTo("300");
        assertThat(anomaly.getAverageAmount()).isEqualByComparingTo("100");
        assertThat(anomaly.getRatio()).isEqualByComparingTo("3.00");
        assertThat(count()).isEqualTo(1.0);
    }

    @Test
    void doesNotFireWhenHistoryIsInsufficient() {
        // Nine prior expenses — one short of the 10 minimum, so no anomaly regardless of average.
        stubBaseline(9L, "1");

        assertThat(service.evaluate(expense(UUID.randomUUID(), "1000000"))).isEmpty();
        verify(anomalyRepository, never()).save(any());
        assertThat(count()).isEqualTo(0.0);
    }

    @Test
    void doesNotFireBelowThreeTimesAverage() {
        stubBaseline(50L, "100");

        assertThat(service.evaluate(expense(UUID.randomUUID(), "299.99"))).isEmpty(); // < 3×
        verify(anomalyRepository, never()).save(any());
    }

    @Test
    void ignoresNonExpenseEvents() {
        TransactionCreatedEvent income = new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", "2026-06-15T10:00:00Z",
                UUID.randomUUID(), USER, "INCOME", new BigDecimal("9999999"),
                CUR, 7L, "2026-06-15", 1L);

        assertThat(service.evaluate(income)).isEmpty();
        verify(expenseRepository, never()).expenseBaselineBefore(any(), any());
        verify(anomalyRepository, never()).save(any());
    }

    /** Stubs the combined prior-expense baseline (count + average) the service reads in one query. */
    private void stubBaseline(long historyCount, String average) {
        ObservedExpenseRepository.ExpenseBaseline baseline =
                mock(ObservedExpenseRepository.ExpenseBaseline.class);
        when(baseline.getCount()).thenReturn(historyCount);
        when(baseline.getAverage()).thenReturn(average == null ? null : new BigDecimal(average));
        when(expenseRepository.expenseBaselineBefore(eq(USER), any())).thenReturn(baseline);
    }

    private TransactionCreatedEvent expense(UUID txId, String amount) {
        return new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", "2026-06-15T10:00:00Z",
                txId, USER, "EXPENSE", new BigDecimal(amount),
                CUR, 7L, "2026-06-15", 1L);
    }

    private double count() {
        return meterRegistry.find(DETECTED)
                .tag("type", AnomalyType.UNUSUAL_TRANSACTION_AMOUNT.name())
                .counter().count();
    }
}
