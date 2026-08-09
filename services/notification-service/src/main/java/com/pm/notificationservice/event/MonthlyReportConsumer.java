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
 * Consumes {@code MonthlyReportReady} and materializes one notification per user per month
 * (Phase G.2) — the month-in-review that then leaves through whichever channels the user has on:
 * bell, SSE, web push, email, webhook.
 *
 * <p>Mirrors {@link BudgetExceededConsumer} exactly: same filter rules, same "every event lands in
 * exactly one counter" discipline, same reliance on the service for wording, idempotency and
 * persistence, and its own container factory because the headerless wire format pins one payload
 * type per factory.
 *
 * <p>"Once per month" is enforced upstream in analytics-service, which records what it has sent.
 * This service only de-duplicates redeliveries of the same event id.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class MonthlyReportConsumer {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportConsumer.class);

    private static final String IGNORED_COUNTER = "finsight.notifications.ignored";

    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;
    private final Counter createdNotifications;
    private final Counter duplicateEvents;

    public MonthlyReportConsumer(NotificationService notificationService, MeterRegistry meterRegistry) {
        this.notificationService = notificationService;
        this.meterRegistry = meterRegistry;
        this.createdNotifications = Counter.builder("finsight.notifications.created")
                .description("RiskDetected events turned into a new in-app notification")
                .register(meterRegistry);
        this.duplicateEvents = Counter.builder("finsight.notifications.duplicate")
                .description("RiskDetected events skipped by the idempotency inbox")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${finsight.kafka.topics.monthly-report}",
            containerFactory = "monthlyReportListenerContainerFactory")
    public void onMonthlyReport(MonthlyReportEvent event) {
        if (event.eventId() == null) {
            log.warn("Ignoring MonthlyReportReady without eventId (cannot de-duplicate): period={}",
                    event.periodMonth());
            ignored("no_event_id");
            return;
        }
        if (event.userId() == null) {
            log.warn("Ignoring MonthlyReportReady {} without userId (no recipient)", event.eventId());
            ignored("no_user_id");
            return;
        }

        boolean created = notificationService.createFromMonthlyReport(event);
        if (created) {
            createdNotifications.increment();
            log.info("Created notification from MonthlyReportReady {}: userId={}, period={}",
                    event.eventId(), event.userId(), event.periodMonth());
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate MonthlyReportReady {}", event.eventId());
        }
    }

    private void ignored(String reason) {
        meterRegistry.counter(IGNORED_COUNTER, "reason", reason).increment();
    }
}
