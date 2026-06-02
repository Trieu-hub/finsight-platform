package com.pm.transactionservice.repository;

import com.pm.transactionservice.dto.TransactionFilterRequest;
import com.pm.transactionservice.entity.Transaction;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the WHERE clause for "fetch transactions by user with filters".
 * Every predicate is scoped by userId so the indexes (user_id, transaction_date),
 * (user_id, type) and category_id are used.
 */
public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    public static Specification<Transaction> forUserWithFilters(Long userId, TransactionFilterRequest filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.equal(root.get("userId"), userId));
            predicates.add(cb.isFalse(root.get("isDeleted")));

            if (filter.getType() != null) {
                predicates.add(cb.equal(root.get("type"), filter.getType()));
            }
            if (filter.getCategoryId() != null) {
                predicates.add(cb.equal(root.get("categoryId"), filter.getCategoryId()));
            }
            if (filter.getFromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("transactionDate"), filter.getFromDate()));
            }
            if (filter.getToDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("transactionDate"), filter.getToDate()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
