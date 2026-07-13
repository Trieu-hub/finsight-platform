package com.pm.transactionservice.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A lockout from the LuckyMe games, applied when a user's wallet goes negative from playing.
 * Rows are append-only; the active ban is the one with the greatest {@code bannedUntil}, and the
 * row count feeds the escalation (see {@link BanTier}).
 */
@Entity
@Table(name = "game_bans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GameBan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** How deep in the red the user was, as a positive amount. */
    @Column(name = "debt", nullable = false, precision = 19, scale = 4)
    private BigDecimal debt;

    @Column(name = "tier", nullable = false, length = 20)
    private String tier;

    @Column(name = "banned_at", nullable = false)
    private Instant bannedAt;

    @Column(name = "banned_until", nullable = false)
    private Instant bannedUntil;
}
