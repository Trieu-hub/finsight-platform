package com.pm.budgetservice.service;

import com.pm.budgetservice.dto.BudgetFilterRequest;
import com.pm.budgetservice.dto.BudgetResponse;
import com.pm.budgetservice.dto.CreateBudgetRequest;
import com.pm.budgetservice.dto.UpdateBudgetRequest;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface BudgetService {

    BudgetResponse create(Long userId, CreateBudgetRequest request);

    Page<BudgetResponse> list(Long userId, BudgetFilterRequest filter);

    BudgetResponse getById(Long userId, UUID id);

    BudgetResponse update(Long userId, UUID id, UpdateBudgetRequest request);

    void delete(Long userId, UUID id);

    /**
     * Applies an EXPENSE from a {@code TransactionCreated} event to every matching
     * active budget (see {@code BudgetRepository#applyExpense} for the matching rules).
     * Driven by the Kafka consumer, not by HTTP.
     *
     * @return true if the event was applied, false if it was a duplicate already
     *         recorded in the processed_events inbox
     */
    boolean applyExpense(UUID eventId, Long userId, Long categoryId, String currency,
                         BigDecimal amount, LocalDate transactionDate);

    /**
     * Reverses a soft-deleted EXPENSE from every matching active budget — the inverse of
     * {@link #applyExpense} (an atomic {@code spent_amount += -amount}). Driven by a
     * {@code TransactionDeleted} event.
     *
     * @return true if applied, false if the eventId was a duplicate already in the inbox
     */
    boolean applyDelete(UUID eventId, Long userId, Long categoryId, String currency,
                        BigDecimal amount, LocalDate transactionDate);

    /**
     * Re-materializes an edited EXPENSE: reverses the {@code reverse} slot and applies the
     * {@code apply} slot, both in one DB transaction under a single inbox row. Either side
     * may be {@code null} when that side of the edit was not an EXPENSE (e.g. an
     * EXPENSE→INCOME edit passes a non-null {@code reverse} and a null {@code apply}).
     * Driven by a {@code TransactionUpdated} event.
     *
     * @return true if applied, false if the eventId was a duplicate already in the inbox
     */
    boolean applyUpdate(UUID eventId, Long userId, ExpenseLine reverse, ExpenseLine apply);
}
