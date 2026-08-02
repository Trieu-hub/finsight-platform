package com.pm.notificationservice.service.impl;

import com.pm.notificationservice.dto.PushSubscriptionRequest;
import com.pm.notificationservice.entity.PushSubscription;
import com.pm.notificationservice.repository.PushSubscriptionRepository;
import com.pm.notificationservice.service.PushSubscriptionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PushSubscriptionServiceImpl implements PushSubscriptionService {

    private final PushSubscriptionRepository repository;

    public PushSubscriptionServiceImpl(PushSubscriptionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void subscribe(Long userId, PushSubscriptionRequest request) {
        // Upsert on the endpoint, which is the browser's identity and carries a UNIQUE constraint.
        // Browsers re-subscribe on their own schedule, and the same endpoint can come back owned by
        // a different signed-in user on a shared machine — both must land on one row, or the push
        // would go to whoever subscribed first.
        PushSubscription subscription = repository.findByEndpoint(request.getEndpoint())
                .orElseGet(() -> PushSubscription.builder()
                        .id(UUID.randomUUID())
                        .endpoint(request.getEndpoint())
                        .createdAt(LocalDateTime.now())
                        .build());

        subscription.setUserId(userId);
        subscription.setP256dh(request.getP256dh());
        subscription.setAuthSecret(request.getAuth());
        repository.save(subscription);
    }

    @Override
    @Transactional
    public void unsubscribe(Long userId, String endpoint) {
        repository.deleteByUserIdAndEndpoint(userId, endpoint);
    }
}
