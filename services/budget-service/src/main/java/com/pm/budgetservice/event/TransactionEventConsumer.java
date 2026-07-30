package com.pm.budgetservice.event;

import com.pm.budgetservice.service.BudgetService;
import com.pm.budgetservice.service.ExpenseLine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the three transaction-lifecycle events and keeps budgets' {@code spent_amount}
 * accurate: {@code TransactionCreated} adds an EXPENSE, {@code TransactionDeleted} reverses
 * one, and {@code TransactionUpdated} reverses the old slot then applies the new one. Thin
 * by design: filtering and parsing happen here; matching, idempotency and the atomic
 * increment live in the service/repository.
 *
 * <p>The created/updated/deleted events travel on separate topics; the JSON payload type
 * per listener is pinned via {@code spring.json.value.default.type} in the
 * {@code @KafkaListener} {@code properties} (the global default in application.yml covers
 * the created listener).
 *
 * <p>Filter rules (events skipped here are NOT recorded in the inbox — re-skipping a
 * redelivered event is free): non-EXPENSE types, and a missing eventId (without it the event
 * cannot be de-duplicated, so applying it could double-count on redelivery — dropping it is the
 * safe failure mode). A missing/unknown {@code budgetId} is NOT filtered: it reaches the service,
 * is recorded in the inbox and increments no budget (the normal "budget-less expense" outcome).
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

        boolean applied = budgetService.applyExpense(event.eventId(), event.userId(),
                event.budgetId(), event.amount());
        if (applied) {
            processedEvents.increment();
            log.info("Applied expense event {} to budget: userId={}, budgetId={}, amount={} {}",
                    event.eventId(), event.userId(), event.budgetId(),
                    event.amount(), event.currency());
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate expense event {}", event.eventId());
        }
    }

    /**
     * A transaction edit. Reverse the old contribution (if it was an EXPENSE) and apply the
     * new one (if it is an EXPENSE); an EXPENSE↔INCOME edit therefore only touches one side.
     * Both sides are carried as {@link ExpenseLine}s in a single idempotent service call.
     */
    @KafkaListener(topics = "${finsight.kafka.topics.transaction-updated}",
            properties = "spring.json.value.default.type=com.pm.budgetservice.event.TransactionUpdatedEvent")
    public void onTransactionUpdated(TransactionUpdatedEvent event) {
        if (event.eventId() == null) {
            log.warn("Ignoring TransactionUpdated without eventId (cannot de-duplicate): txId={}",
                    event.transactionId());
            ignored("no_event_id");
            return;
        }
        boolean reverseOld = EXPENSE.equals(event.oldType());
        boolean applyNew = EXPENSE.equals(event.newType());
        if (!reverseOld && !applyNew) {
            log.debug("Ignoring update event {} — neither side is an EXPENSE (old={}, new={})",
                    event.eventId(), event.oldType(), event.newType());
            ignored("non_expense");
            return;
        }

        ExpenseLine reverse = reverseOld
                ? new ExpenseLine(event.oldBudgetId(), event.oldAmount()) : null;
        ExpenseLine apply = applyNew
                ? new ExpenseLine(event.newBudgetId(), event.newAmount()) : null;

        boolean applied = budgetService.applyUpdate(event.eventId(), event.userId(), reverse, apply);
        if (applied) {
            processedEvents.increment();
            log.info("Applied update event {} to budgets: userId={}, reverse={}, apply={}",
                    event.eventId(), event.userId(), reverse, apply);
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate update event {}", event.eventId());
        }
    }

    /**
     * A transaction soft-delete. Reverse the EXPENSE contribution; non-EXPENSE deletes never
     * touched a budget, so they are ignored.
     */
    @KafkaListener(topics = "${finsight.kafka.topics.transaction-deleted}",
            properties = "spring.json.value.default.type=com.pm.budgetservice.event.TransactionDeletedEvent")
    public void onTransactionDeleted(TransactionDeletedEvent event) {
        if (event.eventId() == null) {
            log.warn("Ignoring TransactionDeleted without eventId (cannot de-duplicate): txId={}",
                    event.transactionId());
            ignored("no_event_id");
            return;
        }
        if (!EXPENSE.equals(event.type())) {
            log.debug("Ignoring non-EXPENSE delete event {} (type={})", event.eventId(), event.type());
            ignored("non_expense");
            return;
        }

        boolean applied = budgetService.applyDelete(event.eventId(), event.userId(),
                event.budgetId(), event.amount());
        if (applied) {
            processedEvents.increment();
            log.info("Reversed delete event {} from budget: userId={}, budgetId={}, amount={} {}",
                    event.eventId(), event.userId(), event.budgetId(),
                    event.amount(), event.currency());
        } else {
            duplicateEvents.increment();
            log.info("Skipped duplicate delete event {}", event.eventId());
        }
    }

    private void ignored(String reason) {
        meterRegistry.counter(IGNORED_COUNTER, "reason", reason).increment();
    }
}
