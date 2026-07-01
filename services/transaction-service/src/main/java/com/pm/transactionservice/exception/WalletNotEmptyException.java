package com.pm.transactionservice.exception;

/** Thrown when deleting a wallet whose balance is not zero. */
public class WalletNotEmptyException extends RuntimeException {

    public WalletNotEmptyException(String message) {
        super(message);
    }
}
