package com.pm.notificationservice.delivery;

import com.pm.notificationservice.entity.Notification;

/**
 * A way of getting a notification out of this service and in front of the user.
 *
 * <p>Every channel is <b>best-effort and must not throw</b>: the notification row is already
 * committed by the time a channel runs, so a failed delivery is a degraded experience, never a
 * lost record and never a reason to fail the Kafka consumer (which would redeliver the event and
 * re-notify everyone who *did* receive it).
 *
 * <p>A channel that is not configured does nothing. That is the normal state for a fresh
 * checkout — the in-app bell and the SSE stream work with no configuration at all.
 */
public interface DeliveryChannel {

    /** Delivers an already-persisted notification. Implementations swallow their own failures. */
    void deliver(Notification notification);
}
