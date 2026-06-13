package com.pm.riskservice.event;

import com.pm.riskservice.entity.BudgetSnapshot;
import com.pm.riskservice.repository.BudgetSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Maintains the local budget read-model (Phase E.2) by consuming {@code BudgetChanged} from
 * budget-service. Each event upserts the {@link BudgetSnapshot} keyed by the source budget id,
 * so the read-model is idempotent under redelivery and last-write-wins on updates.
 *
 * <p>Uses a dedicated listener container factory ({@code budgetEventListenerContainerFactory})
 * because this topic carries a different type than {@code TransactionCreated} and the wire
 * format is headerless (one default type per consumer factory). Gated by
 * {@code finsight.kafka.enabled} like the rest of the event wiring.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class BudgetEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BudgetEventConsumer.class);

    private final BudgetSnapshotRepository repository;

    public BudgetEventConsumer(BudgetSnapshotRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "${finsight.kafka.topics.budget-changed}",
            containerFactory = "budgetEventListenerContainerFactory")
    @Transactional
    public void onBudgetChanged(BudgetChangedEvent event) {
        LocalDate start = parseDate(event.startDate());
        LocalDate end = parseDate(event.endDate());
        if (event.budgetId() == null || start == null || end == null) {
            log.warn("Ignoring BudgetChanged with missing id/dates: budgetId={}", event.budgetId());
            return;
        }
        repository.save(new BudgetSnapshot(
                event.budgetId(), event.userId(), event.categoryId(), event.currency(),
                event.limitAmount(), start, end, event.deleted(), Instant.now()));
        log.info("Budget read-model updated: budgetId={}, userId={}, categoryId={}, limit={} {}, deleted={}",
                event.budgetId(), event.userId(), event.categoryId(),
                event.limitAmount(), event.currency(), event.deleted());
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
