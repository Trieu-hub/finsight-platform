package com.pm.notificationservice.service;

import com.pm.notificationservice.dto.PushSubscriptionRequest;

public interface PushSubscriptionService {

    /**
     * Records a browser as a push target for this user. Idempotent per endpoint: re-subscribing
     * the same browser updates the existing row, and an endpoint that had belonged to another
     * user (a shared machine where someone else signed in) is reassigned rather than duplicated.
     */
    void subscribe(Long userId, PushSubscriptionRequest request);

    /** Removes one browser. Scoped by userId, so it can only ever remove the caller's own. */
    void unsubscribe(Long userId, String endpoint);
}
