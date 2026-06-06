package com.pm.dashboardservice.dto;

import java.math.BigDecimal;

/** One entry in the top-spending-categories list (expense only). */
public record TopCategoryResponse(
        Long categoryId,
        String categoryName,
        BigDecimal amount) {
}
