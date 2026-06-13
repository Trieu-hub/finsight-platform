package com.pm.riskservice.repository;

import com.pm.riskservice.entity.Insight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence + read queries for {@link Insight} (Phase E.1).
 */
public interface InsightRepository extends JpaRepository<Insight, UUID> {

    /** All insights, newest generation first (backs GET /api/v1/insights). */
    List<Insight> findAllByOrderByGeneratedAtDesc();

    /**
     * Whether this (user, type, month, subject) has already been flagged (idempotency). The
     * subject discriminator scopes the dedup per type — see {@code subject_id} in V5.
     */
    boolean existsByUserIdAndInsightTypeAndPeriodMonthAndSubjectId(
            Long userId, String insightType, String periodMonth, String subjectId);
}
