package com.pm.budgetservice.event;

import com.pm.budgetservice.service.BudgetService;
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
 * <p>Gated by {@code finsight.kafka.enabled} (same master switch the producer side
 * uses) so test/local contexts without a broker never try to subscribe.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private static final String EXPENSE = "EXPENSE";

    private final BudgetService budgetService;

    public TransactionEventConsumer(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @KafkaListener(topics = "${finsight.kafka.topics.transaction-created}")
    public void onTransactionCreated(TransactionCreatedEvent event) {
        if (event.eventId() == null) {
            log.warn("Ignoring TransactionCreated without eventId (cannot de-duplicate): txId={}",
                    event.transactionId());
            return;
        }
        if (!EXPENSE.equals(event.type())) {
            log.debug("Ignoring non-EXPENSE event {} (type={})", event.eventId(), event.type());
            return;
        }
        LocalDate transactionDate = parseDate(event);
        if (transactionDate == null) {
            return;
        }

        boolean applied = budgetService.applyExpense(event.eventId(), event.userId(),
                event.categoryId(), event.currency(), event.amount(), transactionDate);
        if (applied) {
            log.info("Applied expense event {} to budgets: userId={}, categoryId={}, amount={} {}",
                    event.eventId(), event.userId(), event.categoryId(),
                    event.amount(), event.currency());
        } else {
            log.info("Skipped duplicate expense event {}", event.eventId());
        }
    }

    private LocalDate parseDate(TransactionCreatedEvent event) {
        if (event.transactionDate() == null) {
            log.warn("Ignoring event {} without transactionDate (cannot match a budget window)",
                    event.eventId());
            return null;
        }
        try {
            return LocalDate.parse(event.transactionDate());
        } catch (DateTimeParseException e) {
            log.warn("Ignoring event {} with unparseable transactionDate '{}'",
                    event.eventId(), event.transactionDate());
            return null;
        }
    }
}
