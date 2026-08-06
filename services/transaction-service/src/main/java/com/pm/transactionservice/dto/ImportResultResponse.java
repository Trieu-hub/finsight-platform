package com.pm.transactionservice.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * What an import actually did. Rows are independent — one rejected row neither stops the ones
 * after it nor undoes the ones before it — so the answer is a tally, not a single status.
 */
@Getter
@Builder
public class ImportResultResponse {

    /** Rows written as new transactions. */
    private int imported;

    /** Rows recognised as already imported, or repeated within this same file. */
    private int duplicates;

    /** Rows the service refused, each naming its own reason. Empty when everything landed. */
    private List<ImportRowError> errors;
}
