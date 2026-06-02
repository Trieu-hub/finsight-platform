package com.pm.transactionservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pm.transactionservice.enums.TransactionType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CategoryResponse {

    private final Long id;
    private final String name;
    private final TransactionType type;
    private final String icon;
    private final String color;

    // @JsonProperty keeps the JSON key "isSystem"; without it Jackson would emit "system".
    @JsonProperty("isSystem")
    private final boolean isSystem;
}
