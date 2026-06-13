package com.pm.riskservice.web;

import com.pm.riskservice.entity.Insight;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * REST view of an {@link Insight}. Keeps the API contract decoupled from the entity.
 */
public record InsightResponse(
        UUID id,
        Long userId,
        String insightType,
        String periodMonth,
        Long categoryId,
        BigDecimal previousAmount,
        BigDecimal currentAmount,
        BigDecimal increasePct,
        Instant generatedAt
) {

    public static InsightResponse from(Insight insight) {
        return new InsightResponse(
                insight.getId(),
                insight.getUserId(),
                insight.getInsightType(),
                insight.getPeriodMonth(),
                insight.getCategoryId(),
                insight.getPreviousAmount(),
                insight.getCurrentAmount(),
                insight.getIncreasePct(),
                insight.getGeneratedAt());
    }
}
