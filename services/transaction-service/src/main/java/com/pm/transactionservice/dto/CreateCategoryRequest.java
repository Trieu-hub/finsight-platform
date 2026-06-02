package com.pm.transactionservice.dto;

import com.pm.transactionservice.enums.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * NOTE: isSystem is intentionally absent — it is server-controlled and always false
 * for user-created categories.
 */
@Getter
@Setter
public class CreateCategoryRequest {

    @NotBlank(message = "name is required")
    @Size(max = 100, message = "name must be at most 100 characters")
    private String name;

    @NotNull(message = "type is required")
    private TransactionType type;

    @Size(max = 50, message = "icon must be at most 50 characters")
    private String icon;

    @Size(max = 20, message = "color must be at most 20 characters")
    private String color;
}
