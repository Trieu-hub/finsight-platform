package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.TransactionType;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Partial update. Only non-null fields are applied. isSystem cannot be changed.
 */
@Getter
@Setter
public class UpdateCategoryRequest {

    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    private TransactionType type;

    @Size(max = 50, message = "icon must be at most 50 characters")
    private String icon;

    @Size(max = 20, message = "color must be at most 20 characters")
    private String color;
}
