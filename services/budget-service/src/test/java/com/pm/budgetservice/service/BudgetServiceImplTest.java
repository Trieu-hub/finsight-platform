package com.pm.budgetservice.service;

import com.pm.budgetservice.audit.AuditLog;
import com.pm.budgetservice.dto.BudgetResponse;
import com.pm.budgetservice.dto.CreateBudgetRequest;
import com.pm.budgetservice.dto.UpdateBudgetRequest;
import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.event.BudgetExceededEvent;
import com.pm.budgetservice.exception.BudgetConflictException;
import com.pm.budgetservice.exception.BudgetNotFoundException;
import com.pm.budgetservice.exception.InvalidBudgetDataException;
import com.pm.budgetservice.entity.ProcessedEvent;
import com.pm.budgetservice.repository.BudgetRepository;
import com.pm.budgetservice.repository.ProcessedEventRepository;
import com.pm.budgetservice.service.impl.BudgetServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for the budget business rules: ownership scoping, the positive-amount
 * and date-range guards, duplicate detection, partial update and soft delete — all with
 * a mocked repository (no Spring context, no database).
 */
@ExtendWith(MockitoExtension.class)
class BudgetServiceImplTest {

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AuditLog auditLog;

    @InjectMocks
    private BudgetServiceImpl budgetService;

    private static final long USER_ID = 42L;

    private CreateBudgetRequest validCreate() {
        CreateBudgetRequest r = new CreateBudgetRequest();
        r.setName("Groceries");
        r.setCategoryId(4L);
        r.setPeriodType(BudgetPeriod.MONTHLY);
        r.setStartDate(LocalDate.of(2026, 6, 1));
        r.setEndDate(LocalDate.of(2026, 6, 30));
        r.setLimitAmount(new BigDecimal("500.00"));
        r.setCurrency("USD");
        return r;
    }

    @Test
    void createPersistsBudgetScopedToTokenUserId() {
        when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> inv.getArgument(0));

        BudgetResponse response = budgetService.create(USER_ID, validCreate());

        ArgumentCaptor<Budget> captor = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository).save(captor.capture());
        Budget saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getCategoryId()).isEqualTo(4L);
        assertThat(response.getUserId()).isEqualTo(USER_ID);
        assertThat(response.getLimitAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void createRejectsNonPositiveLimit() {
        CreateBudgetRequest r = validCreate();
        r.setLimitAmount(new BigDecimal("0.00"));

        assertThatThrownBy(() -> budgetService.create(USER_ID, r))
                .isInstanceOf(InvalidBudgetDataException.class);
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void createRejectsEndBeforeStart() {
        CreateBudgetRequest r = validCreate();
        r.setStartDate(LocalDate.of(2026, 6, 30));
        r.setEndDate(LocalDate.of(2026, 6, 1));

        assertThatThrownBy(() -> budgetService.create(USER_ID, r))
                .isInstanceOf(InvalidBudgetDataException.class);
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateActiveBudget() {
        when(budgetRepository
                .existsByUserIdAndCategoryIdAndPeriodTypeAndStartDateAndIsDeletedFalse(
                        eq(USER_ID), eq(4L), eq(BudgetPeriod.MONTHLY), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(true);

        assertThatThrownBy(() -> budgetService.create(USER_ID, validCreate()))
                .isInstanceOf(BudgetConflictException.class);
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void getByIdThrowsWhenNotOwned() {
        UUID id = UUID.randomUUID();
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(id, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> budgetService.getById(USER_ID, id))
                .isInstanceOf(BudgetNotFoundException.class);
    }

    @Test
    void updateAppliesOnlyNonNullFieldsAndKeepsSlotCheckOff() {
        UUID id = UUID.randomUUID();
        Budget existing = Budget.builder()
                .id(id).userId(USER_ID).name("Old").categoryId(4L)
                .periodType(BudgetPeriod.MONTHLY)
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .limitAmount(new BigDecimal("500.00")).currency("USD").isDeleted(false)
                .build();
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(id, USER_ID))
                .thenReturn(Optional.of(existing));
        when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateBudgetRequest patch = new UpdateBudgetRequest();
        patch.setLimitAmount(new BigDecimal("750.00"));

        BudgetResponse response = budgetService.update(USER_ID, id, patch);

        assertThat(response.getLimitAmount()).isEqualByComparingTo("750.00");
        assertThat(response.getName()).isEqualTo("Old");        // untouched
        assertThat(response.getCategoryId()).isEqualTo(4L);     // untouched
        // The slot (category/period/startDate) did not change, so no duplicate lookup runs.
        verify(budgetRepository, never())
                .existsByUserIdAndCategoryIdAndPeriodTypeAndStartDateAndIsDeletedFalse(
                        anyLong(), anyLong(), any(), any());
    }

    @Test
    void updateChecksDuplicateWhenSlotChanges() {
        UUID id = UUID.randomUUID();
        Budget existing = Budget.builder()
                .id(id).userId(USER_ID).categoryId(4L)
                .periodType(BudgetPeriod.MONTHLY)
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .limitAmount(new BigDecimal("500.00")).currency("USD").isDeleted(false)
                .build();
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(id, USER_ID))
                .thenReturn(Optional.of(existing));
        when(budgetRepository
                .existsByUserIdAndCategoryIdAndPeriodTypeAndStartDateAndIsDeletedFalse(
                        eq(USER_ID), eq(5L), eq(BudgetPeriod.MONTHLY), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(true);

        UpdateBudgetRequest patch = new UpdateBudgetRequest();
        patch.setCategoryId(5L); // changes the slot

        assertThatThrownBy(() -> budgetService.update(USER_ID, id, patch))
                .isInstanceOf(BudgetConflictException.class);
        verify(budgetRepository, never()).save(any());
    }

    @Test
    void applyExpenseRecordsInboxRowAndIncrementsTheChosenBudget() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        boolean applied = budgetService.applyExpense(eventId, USER_ID, budgetId,
                new BigDecimal("42.50"));

        assertThat(applied).isTrue();
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(budgetRepository).applyExpense(USER_ID, budgetId, new BigDecimal("42.50"));
    }

    @Test
    void applyExpenseSkipsDuplicateEventWithoutTouchingBudgets() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        boolean applied = budgetService.applyExpense(eventId, USER_ID, UUID.randomUUID(),
                new BigDecimal("42.50"));

        assertThat(applied).isFalse();
        verify(processedEventRepository, never()).save(any());
        verify(budgetRepository, never()).applyExpense(anyLong(), any(), any());
    }

    // --- BudgetExceeded: fire on the crossing, and only on the crossing ---------------------
    // This event becomes a notification the user actually sees, so "once per crossing" is the
    // whole contract. Emitting it per expense while a budget stays over would mean a push, an
    // email and a bell entry for every coffee bought after the limit was passed.

    @Test
    void applyExpensePublishesBudgetExceededOnTheEventThatCrossesTheLimit() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        // After the increment the budget reads 1,200 against a 1,000 limit; this 300 expense took
        // it from 900 (at or below) to over.
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(budgetId, USER_ID))
                .thenReturn(Optional.of(budgetWith("1000", "1200", budgetId)));

        budgetService.applyExpense(eventId, USER_ID, budgetId, new BigDecimal("300"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(BudgetExceededEvent.class);
        BudgetExceededEvent published = (BudgetExceededEvent) captor.getValue();
        assertThat(published.budgetId()).isEqualTo(budgetId);
        assertThat(published.userId()).isEqualTo(USER_ID);
        assertThat(published.spentAmount()).isEqualByComparingTo("1200");
        assertThat(published.limitAmount()).isEqualByComparingTo("1000");
    }

    @Test
    void applyExpenseStaysSilentOnceTheBudgetIsAlreadyOver() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        // 1,500 after a 300 expense means it was already 1,200 — over the 1,000 limit before this.
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(budgetId, USER_ID))
                .thenReturn(Optional.of(budgetWith("1000", "1500", budgetId)));

        budgetService.applyExpense(eventId, USER_ID, budgetId, new BigDecimal("300"));

        verify(eventPublisher, never()).publishEvent(any(BudgetExceededEvent.class));
    }

    @Test
    void applyExpenseStaysSilentWhileTheBudgetIsStillWithinItsLimit() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(budgetId, USER_ID))
                .thenReturn(Optional.of(budgetWith("1000", "900", budgetId)));

        budgetService.applyExpense(eventId, USER_ID, budgetId, new BigDecimal("300"));

        verify(eventPublisher, never()).publishEvent(any(BudgetExceededEvent.class));
    }

    @Test
    void reachingTheLimitExactlyIsNotExceedingIt() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        // Spending the budget down to zero remaining is hitting the target, not overshooting it.
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(budgetId, USER_ID))
                .thenReturn(Optional.of(budgetWith("1000", "1000", budgetId)));

        budgetService.applyExpense(eventId, USER_ID, budgetId, new BigDecimal("300"));

        verify(eventPublisher, never()).publishEvent(any(BudgetExceededEvent.class));
    }

    @Test
    void reversingAnExpenseNeverAnnouncesAnExceededBudget() {
        // applyDelete negates the amount. A reversal can only lower spend, so treating it as a
        // crossing would be nonsense — and the negative-amount guard is what stops it.
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        budgetService.applyDelete(eventId, USER_ID, budgetId, new BigDecimal("300"));

        verify(eventPublisher, never()).publishEvent(any(BudgetExceededEvent.class));
        verify(budgetRepository, never()).findByIdAndUserIdAndIsDeletedFalse(any(), anyLong());
    }

    private static Budget budgetWith(String limit, String spent, UUID id) {
        return Budget.builder()
                .id(id)
                .userId(USER_ID)
                .categoryId(1L)
                .currency("VND")
                .limitAmount(new BigDecimal(limit))
                .spentAmount(new BigDecimal(spent))
                .periodType(BudgetPeriod.MONTHLY)
                .startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 31))
                .isDeleted(false)
                .build();
    }

    @Test
    void applyDeleteReversesTheExpenseWithANegatedAmount() {
        UUID eventId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        boolean applied = budgetService.applyDelete(eventId, USER_ID, budgetId,
                new BigDecimal("42.50"));

        assertThat(applied).isTrue();
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        // Reversal is the inverse increment: spent_amount += -42.50.
        verify(budgetRepository).applyExpense(USER_ID, budgetId, new BigDecimal("-42.50"));
    }

    @Test
    void applyDeleteSkipsDuplicateEventWithoutTouchingBudgets() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        boolean applied = budgetService.applyDelete(eventId, USER_ID, UUID.randomUUID(),
                new BigDecimal("42.50"));

        assertThat(applied).isFalse();
        verify(processedEventRepository, never()).save(any());
        verify(budgetRepository, never()).applyExpense(anyLong(), any(), any());
    }

    @Test
    void applyUpdateReversesOldBudgetAndAppliesNewBudgetUnderOneInboxRow() {
        UUID eventId = UUID.randomUUID();
        UUID oldBudget = UUID.randomUUID();
        UUID newBudget = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        ExpenseLine reverse = new ExpenseLine(oldBudget, new BigDecimal("30.00"));
        ExpenseLine apply = new ExpenseLine(newBudget, new BigDecimal("50.00"));

        boolean applied = budgetService.applyUpdate(eventId, USER_ID, reverse, apply);

        assertThat(applied).isTrue();
        verify(processedEventRepository).save(any(ProcessedEvent.class));
        verify(budgetRepository).applyExpense(USER_ID, oldBudget, new BigDecimal("-30.00"));
        verify(budgetRepository).applyExpense(USER_ID, newBudget, new BigDecimal("50.00"));
    }

    @Test
    void applyUpdateWithNullApplyOnlyReversesTheOldBudget() {
        UUID eventId = UUID.randomUUID();
        UUID oldBudget = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(false);
        ExpenseLine reverse = new ExpenseLine(oldBudget, new BigDecimal("30.00"));

        // EXPENSE -> INCOME edit: reverse the old contribution, apply nothing.
        boolean applied = budgetService.applyUpdate(eventId, USER_ID, reverse, null);

        assertThat(applied).isTrue();
        verify(budgetRepository).applyExpense(USER_ID, oldBudget, new BigDecimal("-30.00"));
        verify(budgetRepository, never()).applyExpense(eq(USER_ID), eq(oldBudget),
                eq(new BigDecimal("30.00")));
    }

    @Test
    void applyUpdateSkipsDuplicateEventWithoutTouchingBudgets() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);
        ExpenseLine line = new ExpenseLine(UUID.randomUUID(), new BigDecimal("30.00"));

        boolean applied = budgetService.applyUpdate(eventId, USER_ID, line, line);

        assertThat(applied).isFalse();
        verify(processedEventRepository, never()).save(any());
        verify(budgetRepository, never()).applyExpense(anyLong(), any(), any());
    }

    @Test
    void deleteSoftDeletesOwnedBudget() {
        UUID id = UUID.randomUUID();
        Budget existing = Budget.builder()
                .id(id).userId(USER_ID).categoryId(4L)
                .periodType(BudgetPeriod.MONTHLY)
                .startDate(LocalDate.of(2026, 6, 1)).endDate(LocalDate.of(2026, 6, 30))
                .limitAmount(new BigDecimal("500.00")).currency("USD").isDeleted(false)
                .build();
        when(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(id, USER_ID))
                .thenReturn(Optional.of(existing));

        budgetService.delete(USER_ID, id);

        ArgumentCaptor<Budget> captor = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository).save(captor.capture());
        assertThat(captor.getValue().isDeleted()).isTrue();
    }
}
