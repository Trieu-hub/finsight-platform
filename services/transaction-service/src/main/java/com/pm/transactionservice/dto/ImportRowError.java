package com.pm.transactionservice.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * One rejected row. {@code row} is the 1-based position in the submitted list so the client can
 * point at the line the user is looking at, rather than at a transaction id that was never created.
 */
@Getter
@Builder
public class ImportRowError {

    private int row;

    private String message;
}
