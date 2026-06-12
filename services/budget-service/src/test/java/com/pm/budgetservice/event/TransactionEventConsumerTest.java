package com.pm.budgetservice.event;

import com.pm.budgetservice.service.BudgetService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the consumer's filter rules and counter semantics: every consumed
 * event lands in exactly one of processed / duplicate / ignored(reason). The service
 * is mocked — matching and idempotency are covered by the integration test.
 */
@ExtendWith(MockitoExtension.class)
class TransactionEventConsumerTest {

    @Mock
    private BudgetService budgetService;

    private SimpleMeterRegistry meterRegistry;
    private TransactionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new TransactionEventConsumer(budgetService, meterRegistry);
    }

    private TransactionCreatedEvent event(UUID eventId, String type, String transactionDate) {
        return new TransactionCreatedEvent(eventId, "TransactionCreated",
                "2026-06-12T10:00:00Z", UUID.randomUUID(), 42L, type,
                new BigDecimal("42.50"), "USD", 4L, transactionDate, 7L);
    }

    @Test
    void appliedExpenseCountsAsProcessed() {
        UUID eventId = UUID.randomUUID();
        when(budgetService.applyExpense(eq(eventId), eq(42L), eq(4L), eq("USD"),
                eq(new BigDecimal("42.50")), eq(LocalDate.of(2026, 6, 15)))).thenReturn(true);

        consumer.onTransactionCreated(event(eventId, "EXPENSE", "2026-06-15"));

        assertThat(counter("finsight.budget.events.processed")).isEqualTo(1.0);
        assertThat(counter("finsight.budget.events.duplicate")).isEqualTo(0.0);
    }

    @Test
    void duplicateExpenseCountsAsDuplicate() {
        when(budgetService.applyExpense(any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        consumer.onTransactionCreated(event(UUID.randomUUID(), "EXPENSE", "2026-06-15"));

        assertThat(counter("finsight.budget.events.processed")).isEqualTo(0.0);
        assertThat(counter("finsight.budget.events.duplicate")).isEqualTo(1.0);
    }

    @Test
    void nonExpenseIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionCreated(event(UUID.randomUUID(), "INCOME", "2026-06-15"));

        assertThat(ignored("non_expense")).isEqualTo(1.0);
        verify(budgetService, never()).applyExpense(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingEventIdIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionCreated(event(null, "EXPENSE", "2026-06-15"));

        assertThat(ignored("no_event_id")).isEqualTo(1.0);
        verify(budgetService, never()).applyExpense(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingDateIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionCreated(event(UUID.randomUUID(), "EXPENSE", null));

        assertThat(ignored("no_date")).isEqualTo(1.0);
        verify(budgetService, never()).applyExpense(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unparseableDateIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionCreated(event(UUID.randomUUID(), "EXPENSE", "15/06/2026"));

        assertThat(ignored("bad_date")).isEqualTo(1.0);
        verify(budgetService, never()).applyExpense(any(), any(), any(), any(), any(), any());
    }

    private double counter(String name) {
        return meterRegistry.counter(name).count();
    }

    private double ignored(String reason) {
        return meterRegistry.counter("finsight.budget.events.ignored", "reason", reason).count();
    }
}
