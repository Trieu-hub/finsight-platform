package com.pm.riskservice.service;

import com.pm.riskservice.anomaly.AnomalyType;
import com.pm.riskservice.entity.Anomaly;
import com.pm.riskservice.event.EventTimes;
import com.pm.riskservice.event.TransactionCreatedEvent;
import com.pm.riskservice.repository.AnomalyRepository;
import com.pm.riskservice.repository.ObservedExpenseRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Detects anomalies from the {@code observed_expenses} read-model the risk rules already feed —
 * no new ingestion, no ML, no statistical model beyond a simple average, no prediction.
 * Evaluated on each consumed EXPENSE, after the risk engine has recorded it.
 *
 * <ul>
 *   <li><b>UNUSUAL_TRANSACTION_AMOUNT</b> (F.1): the event's amount is at least 3× the user's
 *       average historical expense amount, once at least {@link #MIN_HISTORY} prior EXPENSE
 *       transactions exist. "Prior" = expenses recorded strictly before this event's time, so
 *       the just-recorded triggering expense is excluded from its own baseline.</li>
 * </ul>
 *
 * <p>Detection is idempotent: the anomaly's id is the source event id, so a redelivered event
 * neither double-counts the metric nor inserts a duplicate row.
 */
@Service
public class AnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);

    static final String DETECTED_COUNTER = "finsight.anomalies.detected";

    private static final String EXPENSE = "EXPENSE";
    /** The amount must be at least 3× the user's average historical expense. */
    static final BigDecimal UNUSUAL_AMOUNT_MULTIPLE = new BigDecimal("3");
    /** Minimum prior EXPENSE transactions before the rule may fire. */
    static final long MIN_HISTORY = 10;

    private final ObservedExpenseRepository expenseRepository;
    private final AnomalyRepository anomalyRepository;
    private final Counter detected;

    public AnomalyService(ObservedExpenseRepository expenseRepository,
                          AnomalyRepository anomalyRepository,
                          MeterRegistry meterRegistry) {
        this.expenseRepository = expenseRepository;
        this.anomalyRepository = anomalyRepository;
        // Register eagerly so the series is exported at 0 from startup.
        this.detected = Counter.builder(DETECTED_COUNTER)
                .description("Anomalies detected")
                .tag("type", AnomalyType.UNUSUAL_TRANSACTION_AMOUNT.name())
                .register(meterRegistry);
    }

    /**
     * Evaluates the anomaly rules for {@code event} and returns the anomaly if one was detected.
     * The triggering expense is assumed already recorded (by the risk engine) before this runs.
     */
    @Transactional
    public Optional<Anomaly> evaluate(TransactionCreatedEvent event) {
        if (!EXPENSE.equals(event.type()) || event.amount() == null) {
            return Optional.empty();
        }
        Instant occurredAt = EventTimes.parseInstant(event.occurredAt());
        if (occurredAt == null) {
            return Optional.empty();
        }

        // One pass for both the history count and the average (PF-2).
        ObservedExpenseRepository.ExpenseBaseline baseline =
                expenseRepository.expenseBaselineBefore(event.userId(), occurredAt);
        if (baseline.getCount() < MIN_HISTORY) {
            return Optional.empty();
        }
        BigDecimal average = baseline.getAverage();
        if (average == null || average.signum() <= 0) {
            return Optional.empty();
        }
        if (event.amount().compareTo(average.multiply(UNUSUAL_AMOUNT_MULTIPLE)) < 0) {
            return Optional.empty();
        }
        return persist(event, occurredAt, average);
    }

    /** All anomalies, newest detection first (backs the read API). */
    @Transactional(readOnly = true)
    public List<Anomaly> findAll() {
        return anomalyRepository.findAllByOrderByOccurredAtDesc();
    }

    private Optional<Anomaly> persist(TransactionCreatedEvent event, Instant occurredAt,
                                      BigDecimal average) {
        UUID id = event.eventId() != null ? event.eventId() : UUID.randomUUID();
        if (anomalyRepository.existsById(id)) {
            return Optional.empty();
        }
        BigDecimal ratio = event.amount().divide(average, 2, RoundingMode.HALF_UP);
        Instant now = Instant.now();
        Anomaly anomaly = anomalyRepository.save(new Anomaly(
                id, event.userId(), event.transactionId(),
                AnomalyType.UNUSUAL_TRANSACTION_AMOUNT.name(), event.amount(), average, ratio,
                occurredAt, now));
        detected.increment();
        log.info("Anomaly detected [{}]: userId={}, transactionId={}, amount={}, average={}, ratio={}",
                AnomalyType.UNUSUAL_TRANSACTION_AMOUNT, event.userId(), event.transactionId(),
                event.amount(), average, ratio);
        return Optional.of(anomaly);
    }
}
