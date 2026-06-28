package com.pm.analyticsservice.dto;

import java.math.BigDecimal;

/**
 * One category's expense this month versus last, for the overview's "top movers".
 * {@code changePct} is null when there was no spend in that category last month
 * (a percentage change off zero is undefined).
 */
public record CategoryMover(
        Long categoryId,
        String categoryName,
        BigDecimal amount,
        BigDecimal prevAmount,
        Double changePct
) {
}
