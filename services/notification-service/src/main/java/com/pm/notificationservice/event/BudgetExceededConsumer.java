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
 * Consumes {@code BudgetExceeded} and materializes one in-app notification per crossing.
 *
 * <p>Mirrors {@link RiskDetectedConsumer} exactly — same filter rules, same "every event lands in
 * exactly one counter" discipline, same reliance on the service for narration, idempotency and
 * persistence. The alert then leaves through the same channels as any other: bell, SSE, web push
 * and email.
 *
 * <p>Reads from its own container factory ({@code budgetExceededListenerContainerFactory}): the
 * wire format is headerless, so the payload type is pinned per factory and this one cannot share
 * the auto-configured factory that is pinned to {@link RiskDetectedEvent}.
 *
 * <p>"Fire once per crossing" is enforced upstream in budget-service, not here: this service only
 * de-duplicates redeliveries of the same event id.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class BudgetExceededConsumer {

    private static final Logger log = LoggerFactory.getLogger(BudgetExceededConsumer.class);

    private static final String IGNORED_COUNTER = "finsight.notifications.ignored";

    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;
    private final Counter createdNotifications;
    private final Counter duplicateEvents;

    public BudgetExceededConsumer(NotificationService notificationService, MeterRegistry meterRegistry) {
        this.notificationService = notificationService;
        this.meterRegistry = meterRegistry;
        this.createdNotifications = Counter.builder("finsight.notifications.created")
                .description("RiskDetected events turned into a new in-app notification")
                .register(meterRegistry);
        this.duplicateEvents = Counter.builder("finsight.notifications.duplicate")
                .description("RiskDetected events skipped by the idempotency inbox")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${finsight.kafka.topics.budget-exceeded}",
            containerFactory = "budgetExceededListenerContainerFactory")
    public void onBudgetExceeded(BudgetExceededEvent event) {
        if (event.eventId() == null) {
            log.warn("Ignoring BudgetExceeded without eventId (cannot de-duplicate): budgetId={}",
                    event.budgetId());
            ignored("no_event_id");
            return;
        }
        if (event.userId() == null) {
            log.warn("Ignoring BudgetExceeded {} without userId (no recipient)", event.eventId());
            ignored("no_user_id");
            return;
        }

        boolean created = notificationService.createFromBudgetExceeded(event);
        if (created) {
            createdNotifications.increment();
            log.info("Created notification from BudgetExceeded {}: userId={}, budgetId={}, spent={}, limit={}",
                    event.eventId(), event.userId(), event.budgetId(),
                    event.spentAmount(), event.limitAmount());
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate BudgetExceeded {}", event.eventId());
        }
    }

    private void ignored(String reason) {
        meterRegistry.counter(IGNORED_COUNTER, "reason", reason).increment();
    }
}
