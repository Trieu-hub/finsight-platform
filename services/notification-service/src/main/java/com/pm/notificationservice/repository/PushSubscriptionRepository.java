package com.pm.notificationservice.repository;

import com.pm.notificationservice.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    List<PushSubscription> findByUserId(Long userId);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    /** Scoped by userId as well, so one user can never unsubscribe another user's browser. */
    long deleteByUserIdAndEndpoint(Long userId, String endpoint);
}
