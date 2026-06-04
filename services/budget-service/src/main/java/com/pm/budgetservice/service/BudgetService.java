package com.pm.budgetservice.service;

import com.pm.budgetservice.dto.BudgetFilterRequest;
import com.pm.budgetservice.dto.BudgetResponse;
import com.pm.budgetservice.dto.CreateBudgetRequest;
import com.pm.budgetservice.dto.UpdateBudgetRequest;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface BudgetService {

    BudgetResponse create(Long userId, CreateBudgetRequest request);

    Page<BudgetResponse> list(Long userId, BudgetFilterRequest filter);

    BudgetResponse getById(Long userId, UUID id);

    BudgetResponse update(Long userId, UUID id, UpdateBudgetRequest request);

    void delete(Long userId, UUID id);
}
