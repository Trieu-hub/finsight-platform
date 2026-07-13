package com.pm.transactionservice.game;

import com.pm.transactionservice.dto.CreateTransactionRequest;
import com.pm.transactionservice.entity.Wallet;
import com.pm.transactionservice.enums.TransactionType;
import com.pm.transactionservice.exception.WalletNotFoundException;
import com.pm.transactionservice.game.GameDtos.BanInfo;
import com.pm.transactionservice.game.GameDtos.BetRequest;
import com.pm.transactionservice.game.GameDtos.GameStatus;
import com.pm.transactionservice.game.GameDtos.SettledBet;
import com.pm.transactionservice.game.GameDtos.SpinRequest;
import com.pm.transactionservice.game.GameDtos.SpinResponse;
import com.pm.transactionservice.repository.WalletRepository;
import com.pm.transactionservice.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The LuckyMe games, played with the user's real wallet money (which is itself fake — this whole
 * app tracks invented numbers, so nothing of value is ever at stake).
 *
 * <p>Three things make this server-authoritative, and all three matter:
 * <ol>
 *   <li><b>The server spins.</b> The client never sends an outcome, so it cannot pick one.</li>
 *   <li><b>The server derives the bet type from the covered pockets</b> ({@link Roulette}), so a
 *       client cannot claim a 35:1 payout on an even-money bet.</li>
 *   <li><b>The server writes the money.</b> A round settles to exactly ONE net transaction — a
 *       loss is an EXPENSE, a win is an INCOME — through the normal
 *       {@link TransactionService#create} path, so the wallet balance, the audit log, the Kafka
 *       event and therefore the budgets, analytics and risk rules all see it like any other
 *       money movement. (Writing the stake and the payout as two transactions would double the
 *       row count for no extra information and would drown the user's real ledger.)</li>
 * </ol>
 *
 * <p>Play is allowed while the wallet is in the black. A round may take it negative — the stake is
 * capped at the balance plus a bounded {@link #OVERDRAFT} — and a negative balance triggers a
 * {@link GameBan} whose length grows with the debt and with the number of previous lockouts. The
 * ban lives in the database precisely so that clearing localStorage does not buy another spin.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /** Categories seeded by V7 — losses land in Games, wins in Winnings. (11 is V5's Transfer.) */
    static final long CATEGORY_GAMES = 12L;
    static final long CATEGORY_WINNINGS = 13L;

    /** How far a single round may take the wallet below zero. Bounds the hole you can dig. */
    static final BigDecimal OVERDRAFT = new BigDecimal("10000000");

    private final WalletRepository walletRepository;
    private final GameBanRepository banRepository;
    private final TransactionService transactionService;

    public GameService(WalletRepository walletRepository,
                       GameBanRepository banRepository,
                       TransactionService transactionService) {
        this.walletRepository = walletRepository;
        this.banRepository = banRepository;
        this.transactionService = transactionService;
    }

    @Transactional(readOnly = true)
    public GameStatus status(Long userId, Long walletId) {
        Wallet wallet = resolveWallet(userId, walletId);
        BanInfo ban = activeBan(userId, Instant.now());
        return new GameStatus(
                wallet.getId(),
                wallet.getCurrency(),
                wallet.getBalance(),
                maxStake(wallet.getBalance()),
                canPlay(wallet.getBalance(), ban),
                ban);
    }

    @Transactional
    public SpinResponse spin(Long userId, SpinRequest request) {
        Instant now = Instant.now();

        BanInfo ban = activeBan(userId, now);
        if (ban != null) {
            throw new GameBannedException(
                    "You are locked out of the games until " + ban.bannedUntil(),
                    ban.bannedUntil());
        }

        Wallet wallet = resolveWallet(userId, request.walletId());
        BigDecimal balance = wallet.getBalance();
        if (balance.signum() <= 0) {
            throw new InvalidBetException(
                    "You have no money to play with. Add income to this wallet first.");
        }

        // Classify every chip before taking any money: an illegal position voids the whole round.
        List<Roulette.BetType> types = new ArrayList<>(request.bets().size());
        BigDecimal staked = BigDecimal.ZERO;
        for (BetRequest bet : request.bets()) {
            Roulette.BetType type = Roulette.classify(bet.pockets());
            if (type == null) {
                throw new InvalidBetException(
                        "Not a legal chip position on the table: " + bet.pockets());
            }
            types.add(type);
            staked = staked.add(bet.amount());
        }

        BigDecimal limit = maxStake(balance);
        if (staked.compareTo(limit) > 0) {
            throw new InvalidBetException("Total stake " + staked + " exceeds your limit of " + limit);
        }

        String result = Roulette.spin();

        List<SettledBet> settled = new ArrayList<>(request.bets().size());
        BigDecimal returned = BigDecimal.ZERO;
        for (int i = 0; i < request.bets().size(); i++) {
            BetRequest bet = request.bets().get(i);
            Roulette.BetType type = types.get(i);
            BigDecimal payout = Roulette.payoutFor(type, bet.pockets(), bet.amount(), result);
            returned = returned.add(payout);
            settled.add(new SettledBet(type.name(), bet.pockets(), bet.amount(),
                    payout.signum() > 0, payout));
        }

        BigDecimal net = returned.subtract(staked);
        writeNetTransaction(userId, wallet, net, result);

        // The wallet row was just adjusted by the transaction write in this same DB transaction;
        // the balance it produced is exactly the old balance plus the net (that is what the
        // INCOME/EXPENSE effect does), so derive it rather than re-reading through a stale
        // persistence context.
        BigDecimal after = balance.add(net);

        BanInfo applied = after.signum() < 0 ? applyBan(userId, after.negate(), now) : null;

        log.info("Roulette spin userId={} result={} staked={} returned={} net={} balance={} ban={}",
                userId, result, staked, returned, net, after,
                applied == null ? "none" : applied.tier());

        return new SpinResponse(
                result,
                Roulette.colourOf(result),
                Roulette.WHEEL.indexOf(result),
                settled,
                staked, returned, net,
                after, wallet.getCurrency(),
                canPlay(after, applied),
                applied);
    }

    /**
     * Settles the round to one net transaction. A break-even round writes nothing: a zero-amount
     * transaction is rejected by the domain (amount must be > 0) and would carry no information.
     */
    private void writeNetTransaction(Long userId, Wallet wallet, BigDecimal net, String result) {
        if (net.signum() == 0) {
            return;
        }
        boolean won = net.signum() > 0;

        CreateTransactionRequest tx = new CreateTransactionRequest();
        tx.setType(won ? TransactionType.INCOME : TransactionType.EXPENSE);
        tx.setAmount(net.abs());
        tx.setCurrency(wallet.getCurrency());
        tx.setCategoryId(won ? CATEGORY_WINNINGS : CATEGORY_GAMES);
        tx.setDescription("Roulette — landed on " + result + " (" + Roulette.colourOf(result) + ")");
        tx.setTransactionDate(LocalDate.now());
        tx.setWalletId(wallet.getId());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("game", "roulette");
        metadata.put("result", result);
        tx.setMetadata(metadata);

        transactionService.create(userId, tx);
    }

    /** Records a fresh lockout. Length grows with the debt and with how often this has happened. */
    private BanInfo applyBan(Long userId, BigDecimal debt, Instant now) {
        long priorBans = banRepository.countByUserId(userId);
        BanTier tier = BanTier.of(debt, priorBans);
        Instant until = now.plus(tier.duration());

        banRepository.save(GameBan.builder()
                .userId(userId)
                .debt(debt)
                .tier(tier.name())
                .bannedAt(now)
                .bannedUntil(until)
                .build());

        log.info("Game ban applied userId={} debt={} priorBans={} tier={} until={}",
                userId, debt, priorBans, tier, until);
        return toBanInfo(new GameBan(null, userId, debt, tier.name(), now, until), now);
    }

    private BanInfo activeBan(Long userId, Instant now) {
        return banRepository
                .findFirstByUserIdAndBannedUntilAfterOrderByBannedUntilDesc(userId, now)
                .map(ban -> toBanInfo(ban, now))
                .orElse(null);
    }

    private static BanInfo toBanInfo(GameBan ban, Instant now) {
        long remaining = Math.max(0, ban.getBannedUntil().getEpochSecond() - now.getEpochSecond());
        return new BanInfo(ban.getTier(), ban.getDebt(), ban.getBannedUntil(), remaining);
    }

    /** The most that may be staked in one round: everything you have, plus a bounded overdraft. */
    private static BigDecimal maxStake(BigDecimal balance) {
        return balance.max(BigDecimal.ZERO).add(OVERDRAFT);
    }

    private static boolean canPlay(BigDecimal balance, BanInfo ban) {
        return ban == null && balance.signum() > 0;
    }

    /** The wallet to play from; when unspecified, the user's first one. */
    private Wallet resolveWallet(Long userId, Long walletId) {
        if (walletId != null) {
            return walletRepository.findByIdAndUserIdAndIsDeletedFalse(walletId, userId)
                    .orElseThrow(() -> new WalletNotFoundException("Wallet not found"));
        }
        List<Wallet> wallets = walletRepository.findByUserIdAndIsDeletedFalseOrderByIdAsc(userId);
        if (wallets.isEmpty()) {
            throw new WalletNotFoundException("Create a wallet before playing");
        }
        return wallets.get(0);
    }
}
