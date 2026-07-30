package com.pm.budgetservice.event;

import com.pm.budgetservice.service.BudgetService;
import com.pm.budgetservice.service.ExpenseLine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private TransactionCreatedEvent event(UUID eventId, String type, UUID budgetId) {
        return new TransactionCreatedEvent(eventId, "TransactionCreated",
                "2026-06-12T10:00:00Z", UUID.randomUUID(), 42L, type,
                new BigDecimal("42.50"), "USD", 4L, "2026-06-15", 7L, budgetId);
    }

    private TransactionUpdatedEvent updated(UUID eventId, String oldType, UUID oldBudgetId,
                                            String newType, UUID newBudgetId) {
        return new TransactionUpdatedEvent(eventId, "TransactionUpdated", "2026-06-12T10:00:00Z",
                UUID.randomUUID(), 42L,
                oldType, new BigDecimal("30.00"), "USD", 4L, "2026-06-10", oldBudgetId,
                newType, new BigDecimal("50.00"), "EUR", 5L, "2026-06-20", newBudgetId);
    }

    private TransactionDeletedEvent deleted(UUID eventId, String type, UUID budgetId) {
        return new TransactionDeletedEvent(eventId, "TransactionDeleted", "2026-06-12T10:00:00Z",
                UUID.randomUUID(), 42L, type, new BigDecimal("42.50"), "USD", 4L, "2026-06-15", budgetId);
    }

    @Test
    void appliedExpenseCountsAsProcessed() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(budgetService.applyExpense(eq(eventId), eq(42L), eq(budgetId),
                eq(new BigDecimal("42.50")))).thenReturn(true);

        consumer.onTransactionCreated(event(eventId, "EXPENSE", budgetId));

        assertThat(counter("finsight.budget.events.processed")).isEqualTo(1.0);
        assertThat(counter("finsight.budget.events.duplicate")).isEqualTo(0.0);
    }

    @Test
    void duplicateExpenseCountsAsDuplicate() {
        when(budgetService.applyExpense(any(), any(), any(), any()))
                .thenReturn(false);

        consumer.onTransactionCreated(event(UUID.randomUUID(), "EXPENSE", UUID.randomUUID()));

        assertThat(counter("finsight.budget.events.processed")).isEqualTo(0.0);
        assertThat(counter("finsight.budget.events.duplicate")).isEqualTo(1.0);
    }

    @Test
    void nonExpenseIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionCreated(event(UUID.randomUUID(), "INCOME", null));

        assertThat(ignored("non_expense")).isEqualTo(1.0);
        verify(budgetService, never()).applyExpense(any(), any(), any(), any());
    }

    @Test
    void missingEventIdIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionCreated(event(null, "EXPENSE", UUID.randomUUID()));

        assertThat(ignored("no_event_id")).isEqualTo(1.0);
        verify(budgetService, never()).applyExpense(any(), any(), any(), any());
    }

    @Test
    void expenseWithoutABudgetStillReachesTheService() {
        // A budget-less expense (e.g. the game) is NOT filtered: it reaches the service, which
        // records the inbox row and increments no budget (null budgetId matches nothing).
        UUID eventId = UUID.randomUUID();
        when(budgetService.applyExpense(eq(eventId), eq(42L), isNull(),
                eq(new BigDecimal("42.50")))).thenReturn(true);

        consumer.onTransactionCreated(event(eventId, "EXPENSE", null));

        assertThat(counter("finsight.budget.events.processed")).isEqualTo(1.0);
    }

    @Test
    void updatedBetweenTwoExpensesReversesOldBudgetAndAppliesNewBudget() {
        UUID eventId = UUID.randomUUID();
        UUID oldBudget = UUID.randomUUID();
        UUID newBudget = UUID.randomUUID();
        when(budgetService.applyUpdate(eq(eventId), eq(42L), any(), any())).thenReturn(true);

        consumer.onTransactionUpdated(updated(eventId, "EXPENSE", oldBudget, "EXPENSE", newBudget));

        ArgumentCaptor<ExpenseLine> reverse = ArgumentCaptor.forClass(ExpenseLine.class);
        ArgumentCaptor<ExpenseLine> apply = ArgumentCaptor.forClass(ExpenseLine.class);
        verify(budgetService).applyUpdate(eq(eventId), eq(42L), reverse.capture(), apply.capture());
        assertThat(reverse.getValue().budgetId()).isEqualTo(oldBudget);
        assertThat(reverse.getValue().amount()).isEqualByComparingTo("30.00");
        assertThat(apply.getValue().budgetId()).isEqualTo(newBudget);
        assertThat(apply.getValue().amount()).isEqualByComparingTo("50.00");
        assertThat(counter("finsight.budget.events.processed")).isEqualTo(1.0);
    }

    @Test
    void updatedFromExpenseToIncomeReversesOnlyTheOldBudget() {
        UUID eventId = UUID.randomUUID();
        UUID oldBudget = UUID.randomUUID();
        when(budgetService.applyUpdate(eq(eventId), eq(42L), any(), isNull())).thenReturn(true);

        consumer.onTransactionUpdated(updated(eventId, "EXPENSE", oldBudget, "INCOME", null));

        ArgumentCaptor<ExpenseLine> reverse = ArgumentCaptor.forClass(ExpenseLine.class);
        verify(budgetService).applyUpdate(eq(eventId), eq(42L), reverse.capture(), isNull());
        assertThat(reverse.getValue().budgetId()).isEqualTo(oldBudget);
        assertThat(counter("finsight.budget.events.processed")).isEqualTo(1.0);
    }

    @Test
    void updatedBetweenTwoNonExpensesIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionUpdated(updated(UUID.randomUUID(), "INCOME", null, "INCOME", null));

        assertThat(ignored("non_expense")).isEqualTo(1.0);
        verify(budgetService, never()).applyUpdate(any(), any(), any(), any());
    }

    @Test
    void updatedMissingEventIdIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionUpdated(updated(null, "EXPENSE", UUID.randomUUID(),
                "EXPENSE", UUID.randomUUID()));

        assertThat(ignored("no_event_id")).isEqualTo(1.0);
        verify(budgetService, never()).applyUpdate(any(), any(), any(), any());
    }

    @Test
    void deletedExpenseIsReversedAndCountsAsProcessed() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(budgetService.applyDelete(eq(eventId), eq(42L), eq(budgetId),
                eq(new BigDecimal("42.50")))).thenReturn(true);

        consumer.onTransactionDeleted(deleted(eventId, "EXPENSE", budgetId));

        assertThat(counter("finsight.budget.events.processed")).isEqualTo(1.0);
        assertThat(counter("finsight.budget.events.duplicate")).isEqualTo(0.0);
    }

    @Test
    void deletedNonExpenseIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionDeleted(deleted(UUID.randomUUID(), "INCOME", null));

        assertThat(ignored("non_expense")).isEqualTo(1.0);
        verify(budgetService, never()).applyDelete(any(), any(), any(), any());
    }

    @Test
    void deletedMissingEventIdIsIgnoredWithoutReachingTheService() {
        consumer.onTransactionDeleted(deleted(null, "EXPENSE", UUID.randomUUID()));

        assertThat(ignored("no_event_id")).isEqualTo(1.0);
        verify(budgetService, never()).applyDelete(any(), any(), any(), any());
    }

    private double counter(String name) {
        return meterRegistry.counter(name).count();
    }

    private double ignored(String reason) {
        return meterRegistry.counter("finsight.budget.events.ignored", "reason", reason).count();
    }
}
