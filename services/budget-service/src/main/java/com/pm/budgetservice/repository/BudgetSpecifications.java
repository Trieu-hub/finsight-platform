package com.pm.budgetservice.repository;

import com.pm.budgetservice.dto.BudgetFilterRequest;
import com.pm.budgetservice.entity.Budget;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the WHERE clause for "fetch budgets by user with filters".
 * Every predicate is scoped by userId so the indexes (user_id),
 * (user_id, category_id) and (user_id, start_date, end_date) are used.
 */
public final class BudgetSpecifications {

    private BudgetSpecifications() {
    }

    public static Specification<Budget> forUserWithFilters(Long userId, BudgetFilterRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("userId"), userId));
            predicates.add(cb.isFalse(root.get("isDeleted")));

            if (filter.getCategoryId() != null) {
                predicates.add(cb.equal(root.get("categoryId"), filter.getCategoryId()));
            }
            if (filter.getPeriodType() != null) {
                predicates.add(cb.equal(root.get("periodType"), filter.getPeriodType()));
            }
            if (filter.getActiveOn() != null) {
                // Budgets whose inclusive [startDate, endDate] range contains the given day.
                predicates.add(cb.lessThanOrEqualTo(root.get("startDate"), filter.getActiveOn()));
                predicates.add(cb.greaterThanOrEqualTo(root.get("endDate"), filter.getActiveOn()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
