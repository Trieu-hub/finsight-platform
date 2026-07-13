package com.pm.transactionservice.game;

import java.time.Instant;

/** The user is locked out of the games until {@link #getBannedUntil()}. */
public class GameBannedException extends RuntimeException {

    private final transient Instant bannedUntil;

    public GameBannedException(String message, Instant bannedUntil) {
        super(message);
        this.bannedUntil = bannedUntil;
    }

    public Instant getBannedUntil() {
        return bannedUntil;
    }
}
