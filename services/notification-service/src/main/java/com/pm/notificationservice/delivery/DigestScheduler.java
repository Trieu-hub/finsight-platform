package com.pm.notificationservice.delivery;

import com.pm.notificationservice.entity.DigestMode;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.service.NotificationPreferenceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sends the batched deliveries that {@link com.pm.notificationservice.service.NotificationService}
 * deliberately did not send, for the users who asked for a digest instead of a stream.
 *
 * <p>The rule is one line: a user's window is due when their <b>oldest</b> pending alert is older
 * than the window. That means the clock starts at the first alert rather than at a fixed hour, so a
 * quiet user is never woken by an empty digest and a busy one gets at most one delivery per window.
 * It also needs no "last sent at" column — the pending rows are the state.
 *
 * <p>The poll interval is the resolution, not the window: at the 5-minute default an hourly digest
 * goes out somewhere between 60 and 65 minutes after the first alert. Tightening it buys precision
 * nobody asked for at the cost of a query every few seconds.
 *
 * <p><b>Single instance.</b> Two of these would each pick up the same pending rows and send the
 * digest twice. Same constraint as the SSE registry in {@code stream/NotificationStream}, and the
 * same reason: this deployment runs one instance. Running more would need the flush to claim its
 * rows first (a conditional update on {@code digested_at}) rather than read-then-write.
 */
@Component
public class DigestScheduler {

    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

    /**
     * Most alerts one flush will carry. A user past this gets the remainder in the next run rather
     * than one unbounded query and one enormous email.
     */
    private static final int MAX_BATCH = 100;

    private final NotificationRepository notifications;
    private final NotificationPreferenceService preferences;
    private final List<DeliveryChannel> channels;
    private final Counter sent;

    public DigestScheduler(NotificationRepository notifications,
                           NotificationPreferenceService preferences,
                           List<DeliveryChannel> channels,
                           MeterRegistry meterRegistry) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.channels = channels;
        this.sent = Counter.builder("finsight.notifications.digest.sent")
                .description("Digests handed to the delivery channels")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${finsight.digest.poll-ms:300000}")
    public void flushDueDigests() {
        for (Long userId : notifications.findUserIdsWithPendingDigest()) {
            try {
                flushUser(userId);
            } catch (RuntimeException e) {
                // One user's bad state must not stop everyone else's digest.
                log.warn("Digest flush failed for user {}: {}", userId, e.toString());
            }
        }
    }

    /**
     * Package-private and deliberately <b>not</b> {@code @Transactional}: this is called from
     * {@link #flushDueDigests()} on the same bean, where Spring's proxy would not apply the
     * annotation anyway, and the delivery below makes HTTP calls that have no business holding a
     * database connection. The one write — stamping the batch — carries its own transaction on the
     * repository method.
     */
    void flushUser(Long userId) {
        DigestMode mode = preferences.get(userId).getDigestMode();
        List<Notification> pending = notifications.findByUserIdAndDigestedAtIsNullOrderByCreatedAtAsc(
                userId, PageRequest.of(0, MAX_BATCH));
        if (pending.isEmpty()) {
            return;
        }

        if (!mode.isDeferred()) {
            // The user is on IMMEDIATE, so these were already delivered as they arrived — or would
            // have been. Stamping them keeps the pending set from growing without bound; sending
            // them would be a duplicate.
            stamp(pending);
            return;
        }

        LocalDateTime due = pending.get(0).getCreatedAt().plus(mode.window());
        if (due.isAfter(LocalDateTime.now())) {
            return;
        }

        // Stamp BEFORE delivering. A channel that fails is best-effort by contract, and the
        // alternative — stamp after — turns one broken receiver into the same digest resent every
        // five minutes forever.
        stamp(pending);
        for (DeliveryChannel channel : channels) {
            if (!channel.respectsDigest()) {
                continue;
            }
            try {
                channel.deliver(userId, pending);
            } catch (RuntimeException e) {
                log.warn("Digest channel {} failed for user {}: {}",
                        channel.getClass().getSimpleName(), userId, e.toString());
            }
        }
        sent.increment();
        log.debug("Sent {} digest of {} alerts to user {}", mode, pending.size(), userId);
    }

    private void stamp(List<Notification> batch) {
        notifications.markDigested(batch.stream().map(Notification::getId).toList(),
                LocalDateTime.now());
    }
}
