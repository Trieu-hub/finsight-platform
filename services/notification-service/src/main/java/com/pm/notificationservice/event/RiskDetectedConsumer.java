package com.pm.notificationservice.event;

import com.pm.notificationservice.service.NotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code RiskDetected} and materializes one in-app notification per detection.
 * Thin by design: filtering happens here; narration, idempotency and persistence live in
 * the service.
 *
 * <p>Filter rules (skipped events are NOT recorded in the inbox — re-skipping a redelivery
 * is free): a missing eventId (without it the event cannot be de-duplicated, so creating a
 * notification could duplicate on redelivery — dropping it is the safe failure mode) and a
 * missing userId (a notification with no recipient is meaningless).
 *
 * <p>Every consumed event lands in exactly one counter: {@code created} (a new notification),
 * {@code duplicate} (inbox dedup hit) or {@code ignored} (filtered out, tagged with reason).
 *
 * <p>Gated by {@code finsight.kafka.enabled} so test/local contexts without a broker never
 * try to subscribe.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class RiskDetectedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiskDetectedConsumer.class);

    private static final String IGNORED_COUNTER = "finsight.notifications.ignored";

    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;
    private final Counter createdNotifications;
    private final Counter duplicateEvents;

    public RiskDetectedConsumer(NotificationService notificationService, MeterRegistry meterRegistry) {
        this.notificationService = notificationService;
        this.meterRegistry = meterRegistry;
        this.createdNotifications = Counter.builder("finsight.notifications.created")
                .description("RiskDetected events turned into a new in-app notification")
                .register(meterRegistry);
        this.duplicateEvents = Counter.builder("finsight.notifications.duplicate")
                .description("RiskDetected events skipped by the idempotency inbox")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${finsight.kafka.topics.risk-detected}")
    public void onRiskDetected(RiskDetectedEvent event) {
        if (event.eventId() == null) {
            log.warn("Ignoring RiskDetected without eventId (cannot de-duplicate): txId={}",
                    event.transactionId());
            ignored("no_event_id");
            return;
        }
        if (event.userId() == null) {
            log.warn("Ignoring RiskDetected {} without userId (no recipient)", event.eventId());
            ignored("no_user_id");
            return;
        }

        boolean created = notificationService.createFromEvent(event);
        if (created) {
            createdNotifications.increment();
            log.info("Created notification from RiskDetected {}: userId={}, riskType={}, severity={}",
                    event.eventId(), event.userId(), event.riskType(), event.riskSeverity());
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate RiskDetected {}", event.eventId());
        }
    }

    private void ignored(String reason) {
        meterRegistry.counter(IGNORED_COUNTER, "reason", reason).increment();
    }
}
