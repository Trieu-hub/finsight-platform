package com.pm.transactionservice.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PageMeta {

    private final int page;
    private final int limit;
    private final long total;
}
