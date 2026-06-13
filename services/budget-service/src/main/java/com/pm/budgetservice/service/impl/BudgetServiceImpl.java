package com.pm.budgetservice.service.impl;

import com.pm.budgetservice.dto.BudgetFilterRequest;
import com.pm.budgetservice.dto.BudgetResponse;
import com.pm.budgetservice.dto.CreateBudgetRequest;
import com.pm.budgetservice.dto.UpdateBudgetRequest;
import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.entity.ProcessedEvent;
import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.event.BudgetChangedEvent;
import com.pm.budgetservice.exception.BudgetConflictException;
import com.pm.budgetservice.exception.BudgetNotFoundException;
import com.pm.budgetservice.exception.InvalidBudgetDataException;
import com.pm.budgetservice.repository.BudgetRepository;
import com.pm.budgetservice.repository.BudgetSpecifications;
import com.pm.budgetservice.repository.ProcessedEventRepository;
import com.pm.budgetservice.service.BudgetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class BudgetServiceImpl implements BudgetService {

    private final BudgetRepository budgetRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ApplicationEventPublisher eventPublisher;

    public BudgetServiceImpl(BudgetRepository budgetRepository,
                             ProcessedEventRepository processedEventRepository,
                             ApplicationEventPublisher eventPublisher) {
        this.budgetRepository = budgetRepository;
        this.processedEventRepository = processedEventRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public BudgetResponse create(Long userId, CreateBudgetRequest request) {
        validateAmountPositive(request.getLimitAmount());
        validateDateRange(request.getStartDate(), request.getEndDate());
        validateNoDuplicate(userId, request.getCategoryId(),
                request.getPeriodType(), request.getStartDate());

        Budget budget = Budget.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .name(request.getName())
                .categoryId(request.getCategoryId())
                .periodType(request.getPeriodType())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .limitAmount(request.getLimitAmount())
                .currency(request.getCurrency())
                .isDeleted(false)
                .build();

        Budget saved = budgetRepository.save(budget);
        // Emitted to Kafka only AFTER this transaction commits (see BudgetEventListener).
        eventPublisher.publishEvent(BudgetChangedEvent.of(saved, false));
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BudgetResponse> list(Long userId, BudgetFilterRequest filter) {
        // page is 1-based in the API, 0-based in Spring Data.
        // Secondary sort on the (unique) id makes paging deterministic when several
        // budgets share the same startDate.
        Sort sort = Sort.by(Sort.Direction.DESC, "startDate")
                .and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageRequest.of(filter.getPage() - 1, filter.getLimit(), sort);

        Specification<Budget> spec = BudgetSpecifications.forUserWithFilters(userId, filter);

        return budgetRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public BudgetResponse getById(Long userId, UUID id) {
        return toResponse(requireOwnedBudget(userId, id));
    }

    @Override
    @Transactional
    public BudgetResponse update(Long userId, UUID id, UpdateBudgetRequest request) {
        Budget budget = requireOwnedBudget(userId, id);

        // Effective slot values after the (partial) update — used for range + duplicate checks.
        Long newCategoryId = request.getCategoryId() != null ? request.getCategoryId() : budget.getCategoryId();
        BudgetPeriod newPeriod = request.getPeriodType() != null ? request.getPeriodType() : budget.getPeriodType();
        LocalDate newStart = request.getStartDate() != null ? request.getStartDate() : budget.getStartDate();
        LocalDate newEnd = request.getEndDate() != null ? request.getEndDate() : budget.getEndDate();

        validateDateRange(newStart, newEnd);

        boolean slotChanged = !newCategoryId.equals(budget.getCategoryId())
                || newPeriod != budget.getPeriodType()
                || !newStart.equals(budget.getStartDate());
        if (slotChanged) {
            validateNoDuplicate(userId, newCategoryId, newPeriod, newStart);
        }

        if (request.getName() != null) {
            budget.setName(request.getName());
        }
        budget.setCategoryId(newCategoryId);
        budget.setPeriodType(newPeriod);
        budget.setStartDate(newStart);
        budget.setEndDate(newEnd);
        if (request.getLimitAmount() != null) {
            validateAmountPositive(request.getLimitAmount());
            budget.setLimitAmount(request.getLimitAmount());
        }
        if (request.getCurrency() != null) {
            budget.setCurrency(request.getCurrency());
        }

        Budget saved = budgetRepository.save(budget);
        eventPublisher.publishEvent(BudgetChangedEvent.of(saved, false));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(Long userId, UUID id) {
        Budget budget = requireOwnedBudget(userId, id);
        // Soft delete.
        budget.setDeleted(true);
        budgetRepository.save(budget);
        // Tell consumers to stop matching this budget.
        eventPublisher.publishEvent(BudgetChangedEvent.of(budget, true));
    }

    @Override
    @Transactional
    public boolean applyExpense(UUID eventId, Long userId, Long categoryId, String currency,
                                BigDecimal amount, LocalDate transactionDate) {
        // Idempotency inbox: an existence check (not insert-and-catch) because a flush
        // failure inside this transaction would mark it rollback-only. Duplicates only
        // arrive sequentially (one consumer per partition), so check-then-insert is
        // race-free in practice; the PK still backstops correctness — a true race would
        // roll back this whole transaction and the redelivery lands here again.
        if (processedEventRepository.existsById(eventId)) {
            return false;
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(LocalDateTime.now())
                .build());

        // Same DB transaction as the inbox row: both commit or neither does.
        budgetRepository.applyExpense(userId, categoryId, currency, amount, transactionDate);
        return true;
    }

    private Budget requireOwnedBudget(Long userId, UUID id) {
        return budgetRepository.findByIdAndUserIdAndIsDeletedFalse(id, userId)
                .orElseThrow(() -> new BudgetNotFoundException("Budget not found"));
    }

    private void validateAmountPositive(BigDecimal amount) {
        // Service-level guard mirroring the DB CHECK (limit_amount > 0). Protects callers
        // that bypass bean validation and turns the DB violation into a 400, not a 500.
        if (amount != null && amount.signum() <= 0) {
            throw new InvalidBudgetDataException("limitAmount must be greater than 0");
        }
    }

    private void validateDateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new InvalidBudgetDataException("endDate must be on or after startDate");
        }
    }

    private void validateNoDuplicate(Long userId, Long categoryId,
                                     BudgetPeriod periodType, LocalDate startDate) {
        boolean exists = budgetRepository
                .existsByUserIdAndCategoryIdAndPeriodTypeAndStartDateAndIsDeletedFalse(
                        userId, categoryId, periodType, startDate);
        if (exists) {
            throw new BudgetConflictException(
                    "A budget for this category, period and start date already exists");
        }
    }

    private BudgetResponse toResponse(Budget b) {
        return BudgetResponse.builder()
                .id(b.getId())
                .userId(b.getUserId())
                .name(b.getName())
                .categoryId(b.getCategoryId())
                .periodType(b.getPeriodType())
                .startDate(b.getStartDate())
                .endDate(b.getEndDate())
                .limitAmount(b.getLimitAmount())
                .spentAmount(b.getSpentAmount())
                .currency(b.getCurrency())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}
