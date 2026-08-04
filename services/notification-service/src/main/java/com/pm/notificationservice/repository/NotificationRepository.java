package com.pm.notificationservice.repository;

import com.pm.notificationservice.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Notification persistence. Every query is scoped by {@code userId} so a caller can
 * only ever reach their own notifications.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadFalseOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);

    Optional<Notification> findByIdAndUserId(UUID id, Long userId);

    /** Marks every unread notification of the user as read; returns the rows affected. */
    @Modifying
    @Query("update Notification n set n.read = true, n.readAt = :now "
            + "where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** Whose alerts are still owed an email/webhook. The digest scheduler's entry point. */
    @Query("select distinct n.userId from Notification n where n.digestedAt is null")
    List<Long> findUserIdsWithPendingDigest();

    /** Oldest first, so a digest reads in the order things happened. */
    List<Notification> findByUserIdAndDigestedAtIsNullOrderByCreatedAtAsc(Long userId, Pageable pageable);

    /**
     * Stamps exactly the rows that went out, by id — not "everything still pending for this user".
     * An alert arriving between building the batch and stamping it would be marked delivered
     * without ever having been sent.
     */
    // Carries its own transaction, unlike markAllRead above, because the digest scheduler calls it
    // from outside one on purpose: the delivery that follows makes HTTP calls, and those must not
    // run with a database connection held open.
    @Modifying
    @Transactional
    @Query("update Notification n set n.digestedAt = :now where n.id in :ids")
    int markDigested(@Param("ids") Collection<UUID> ids, @Param("now") LocalDateTime now);
}
