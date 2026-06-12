package com.pm.budgetservice.event;

import com.pm.budgetservice.service.BudgetService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Consumes {@code TransactionCreated} and applies EXPENSE amounts to matching budgets'
 * {@code spent_amount}. Thin by design: filtering and parsing happen here; matching,
 * idempotency and the atomic increment live in the service/repository.
 *
 * <p>Filter rules (events skipped here are NOT recorded in the inbox — re-skipping a
 * redelivered event is free): non-EXPENSE types, a missing/unparseable transactionDate,
 * and a missing eventId (without it the event cannot be de-duplicated, so applying it
 * could double-count on redelivery — dropping it is the safe failure mode).
 *
 * <p>Every consumed event lands in exactly one counter: {@code processed} (passed all
 * filters and was applied — even if it matched zero budgets), {@code duplicate} (inbox
 * dedup hit) or {@code ignored} (filtered out, tagged with the reason).
 *
 * <p>Gated by {@code finsight.kafka.enabled} (same master switch the producer side
 * uses) so test/local contexts without a broker never try to subscribe.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private static final String EXPENSE = "EXPENSE";
    private static final String IGNORED_COUNTER = "finsight.budget.events.ignored";

    private final BudgetService budgetService;
    private final MeterRegistry meterRegistry;
    private final Counter processedEvents;
    private final Counter duplicateEvents;

    public TransactionEventConsumer(BudgetService budgetService, MeterRegistry meterRegistry) {
        this.budgetService = budgetService;
        this.meterRegistry = meterRegistry;
        this.processedEvents = Counter.builder("finsight.budget.events.processed")
                .description("TransactionCreated events applied to budget utilization "
                        + "(including events that matched no budget)")
                .register(meterRegistry);
        this.duplicateEvents = Counter.builder("finsight.budget.events.duplicate")
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
        if (!EXPENSE.equals(event.type())) {
            log.debug("Ignoring non-EXPENSE event {} (type={})", event.eventId(), event.type());
            ignored("non_expense");
            return;
        }
        LocalDate transactionDate = parseDate(event);
        if (transactionDate == null) {
            return;
        }

        boolean applied = budgetService.applyExpense(event.eventId(), event.userId(),
                event.categoryId(), event.currency(), event.amount(), transactionDate);
        if (applied) {
            processedEvents.increment();
            log.info("Applied expense event {} to budgets: userId={}, categoryId={}, amount={} {}",
                    event.eventId(), event.userId(), event.categoryId(),
                    event.amount(), event.currency());
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate expense event {}", event.eventId());
        }
    }

    private LocalDate parseDate(TransactionCreatedEvent event) {
        if (event.transactionDate() == null) {
            log.warn("Ignoring event {} without transactionDate (cannot match a budget window)",
                    event.eventId());
            ignored("no_date");
            return null;
        }
        try {
            return LocalDate.parse(event.transactionDate());
        } catch (DateTimeParseException e) {
            log.warn("Ignoring event {} with unparseable transactionDate '{}'",
                    event.eventId(), event.transactionDate());
            ignored("bad_date");
            return null;
        }
    }

    private void ignored(String reason) {
        meterRegistry.counter(IGNORED_COUNTER, "reason", reason).increment();
    }
}
