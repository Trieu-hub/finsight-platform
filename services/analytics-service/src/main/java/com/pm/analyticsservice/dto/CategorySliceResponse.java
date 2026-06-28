package com.pm.analyticsservice.dto;

import java.math.BigDecimal;

/**
 * One category's total over the requested range, with its share (%) of that type's
 * total (an EXPENSE slice's share is of all expense; an INCOME slice's of all income).
 */
public record CategorySliceResponse(
        Long categoryId,
        String categoryName,
        String type,
        BigDecimal total,
        int txnCount,
        double share
) {
}
