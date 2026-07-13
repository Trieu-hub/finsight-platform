package com.pm.transactionservice.game;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Wire contracts for the LuckyMe game endpoints. Records — these are immutable payloads. */
public final class GameDtos {

    private GameDtos() {
    }

    /**
     * One chip on the felt. Only the covered pockets are sent: the bet type (and therefore the
     * payout) is <b>derived server-side</b> from the pockets, so a client cannot ask for 35:1 on
     * a red/black bet.
     */
    public record BetRequest(
            @NotEmpty(message = "pockets is required")
            @Size(max = 18, message = "a single chip cannot cover more than 18 pockets")
            List<String> pockets,

            @NotNull(message = "amount is required")
            @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
            BigDecimal amount) {
    }

    /** A round: the wallet being played from, and every chip on the table. */
    public record SpinRequest(
            @NotNull(message = "walletId is required")
            Long walletId,

            @Valid
            @NotEmpty(message = "at least one bet is required")
            @Size(max = 50, message = "at most 50 bets per spin")
            List<BetRequest> bets) {
    }

    /** How one chip resolved. */
    public record SettledBet(String type, List<String> pockets, BigDecimal amount,
                             boolean won, BigDecimal payout) {
    }

    /** An active lockout. */
    public record BanInfo(String tier, BigDecimal debt, Instant bannedUntil, long secondsRemaining) {
    }

    /** The outcome of a round, plus the wallet and lockout state it left behind. */
    public record SpinResponse(String result, String colour, int pocketIndex,
                               List<SettledBet> bets,
                               BigDecimal staked, BigDecimal returned, BigDecimal net,
                               BigDecimal balance, String currency,
                               boolean canPlay, BanInfo ban) {
    }

    /** Everything the game screen needs before a round: the money, and whether play is allowed. */
    public record GameStatus(Long walletId, String currency, BigDecimal balance,
                             BigDecimal maxStake, boolean canPlay, BanInfo ban) {
    }
}
