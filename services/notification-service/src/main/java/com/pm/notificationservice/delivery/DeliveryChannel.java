package com.pm.notificationservice.delivery;

import com.pm.notificationservice.entity.Notification;

import java.util.List;

/**
 * A way of getting notifications out of this service and in front of the user.
 *
 * <p>Every channel is <b>best-effort and must not throw</b>: the notification rows are already
 * committed by the time a channel runs, so a failed delivery is a degraded experience, never a
 * lost record and never a reason to fail the Kafka consumer (which would redeliver the event and
 * re-notify everyone who *did* receive it).
 *
 * <p>A channel that is not configured does nothing. That is the normal state for a fresh
 * checkout — the in-app bell and the SSE stream work with no configuration at all.
 *
 * <p><b>Why a batch and not a single notification.</b> A user on a digest schedule gets one
 * delivery covering everything that arrived in the window, and the immediate path is simply a
 * batch of one. Passing the list to the channel keeps that decision in one place — the caller —
 * instead of asking every channel to learn about scheduling.
 */
public interface DeliveryChannel {

    /**
     * Delivers already-persisted notifications belonging to one user, newest last. Never empty.
     * Implementations swallow their own failures.
     */
    void deliver(Long userId, List<Notification> batch);

    /**
     * Whether this channel is held back by a user's digest setting.
     *
     * <p>False by default, which is right for web push: the push carries no payload, so batching
     * it would delay the nudge without sparing the user anything to read. Channels that carry the
     * alert text — email, webhook — override this to true and are then driven by the digest
     * scheduler instead of by each incoming event.
     */
    default boolean respectsDigest() {
        return false;
    }
}
