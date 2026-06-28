package com.pm.analyticsservice.event;

import com.pm.analyticsservice.service.RollupService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code TransactionCreated} and folds each into the monthly rollup read model.
 * Thin by design: filtering happens here; idempotency and the upsert live in
 * {@link RollupService}.
 *
 * <p>Filter rules (skipped events are NOT recorded in the inbox — re-skipping a
 * redelivery is free): a missing eventId (cannot de-duplicate), a missing userId (the
 * rollup is user-scoped), or a missing type/amount (nothing to aggregate).
 *
 * <p>Every consumed event lands in exactly one counter: {@code applied} (folded into a
 * rollup), {@code duplicate} (inbox dedup hit) or {@code ignored} (filtered, tagged with
 * a reason). Gated by {@code finsight.kafka.enabled} so test/local contexts without a
 * broker never subscribe.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class TransactionCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionCreatedConsumer.class);

    private static final String IGNORED_COUNTER = "finsight.analytics.ignored";

    private final RollupService rollupService;
    private final MeterRegistry meterRegistry;
    private final Counter appliedEvents;
    private final Counter duplicateEvents;

    public TransactionCreatedConsumer(RollupService rollupService, MeterRegistry meterRegistry) {
        this.rollupService = rollupService;
        this.meterRegistry = meterRegistry;
        this.appliedEvents = Counter.builder("finsight.analytics.applied")
                .description("TransactionCreated events folded into the monthly rollup")
                .register(meterRegistry);
        this.duplicateEvents = Counter.builder("finsight.analytics.duplicate")
                .description("TransactionCreated events skipped by the idempotency inbox")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${finsight.kafka.topics.transaction-created}")
    public void onTransactionCreated(TransactionCreatedEvent event) {
        if (event.eventId() == null) {
            log.warn("Ignoring TransactionCreated without eventId (cannot de-duplicate): txId={}",
                    event.transactionId());
            ignored("no_event_id");
            return;
        }
        if (event.userId() == null) {
            log.warn("Ignoring TransactionCreated {} without userId", event.eventId());
            ignored("no_user_id");
            return;
        }
        if (event.type() == null || event.amount() == null) {
            log.warn("Ignoring TransactionCreated {} with no type/amount", event.eventId());
            ignored("incomplete");
            return;
        }

        boolean applied = rollupService.apply(event);
        if (applied) {
            appliedEvents.increment();
            log.debug("Applied TransactionCreated {} to rollup: userId={}, type={}",
                    event.eventId(), event.userId(), event.type());
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate TransactionCreated {}", event.eventId());
        }
    }

    private void ignored(String reason) {
        meterRegistry.counter(IGNORED_COUNTER, "reason", reason).increment();
    }
}
