package com.pm.transactionservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * A statement upload, already parsed into rows by the client. The rows are ordinary
 * {@link CreateTransactionRequest}s — an imported transaction is not a different kind of
 * transaction, so it carries the same fields and the same validation.
 *
 * <p>NOTE: userId is intentionally absent here too. It is resolved from the JWT.
 *
 * <p>The cap is deliberately below what the gateway's 2 MB body limit would allow: a statement
 * larger than this is a sign the user picked the wrong file, and one request should not be able to
 * hold a DB connection for thousands of inserts.
 */
@Getter
@Setter
public class ImportTransactionsRequest {

    @NotEmpty(message = "transactions must not be empty")
    @Size(max = 1000, message = "an import is limited to 1000 rows at a time")
    private List<@Valid CreateTransactionRequest> transactions;
}
