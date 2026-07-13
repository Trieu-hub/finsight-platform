package com.pm.transactionservice.game;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface GameBanRepository extends JpaRepository<GameBan, Long> {

    /** The user's live lockout, if any — the row whose window has not yet expired. */
    Optional<GameBan> findFirstByUserIdAndBannedUntilAfterOrderByBannedUntilDesc(
            Long userId, Instant now);

    /** Total lockouts ever served — drives the repeat-offender escalation. */
    long countByUserId(Long userId);
}
