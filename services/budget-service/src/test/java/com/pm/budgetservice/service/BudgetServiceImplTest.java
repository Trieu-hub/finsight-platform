package com.pm.budgetservice.service;

import com.pm.budgetservice.dto.BudgetResponse;
import com.pm.budgetservice.dto.CreateBudgetRequest;
import com.pm.budgetservice.dto.UpdateBudgetRequest;
import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.exception.BudgetConflictException;
import com.pm.budgetservice.exception.BudgetNotFoundException;
import com.pm.budgetservice.exception.InvalidBudgetDataException;
import com.pm.budgetservice.repository.BudgetRepository;
import com.pm.budgetservice.service.impl.BudgetServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
