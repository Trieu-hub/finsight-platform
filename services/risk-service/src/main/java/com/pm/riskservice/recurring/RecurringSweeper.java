package com.pm.riskservice.recurring;

import com.pm.riskservice.entity.RecurringSeries;
import com.pm.riskservice.event.RiskDetectionEmitter;
import com.pm.riskservice.repository.RecurringSeriesRepository;
import com.pm.riskservice.rule.RiskRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Reports the recurring charge that did not arrive (Phase G.1). This is the one signal in the
 * intelligence layer that cannot be derived on the event path: its trigger is an <em>absence</em>,
 * and no {@code TransactionCreated} is ever published for a payment that failed or a
 * subscription that was cancelled. So it is a poll over {@code recurring_series} instead.
 *
 * <p>A series is reported once and then marked {@code LAPSED}. Without that it would be
 * re-reported on every sweep for the rest of time, and the alert would train the user to ignore
 * it. If the charge does come back later, the ordinary detector path opens a fresh series from
 * it — a resumed subscription is a new commitment worth confirming again.
 *
 * <p>The alert carries the series' last transaction rather than none: {@code risk_alerts} and
 * the {@code RiskDetected} contract both require a transaction, and "the charge that should
 * have repeated" is the most useful thing to point at anyway.
 *
 * <p><b>Single instance</b>, like the notification digest scheduler and the outbox relay: two
 * sweepers would each claim the same overdue series and emit the alert twice.
 *
 * <p>Gated on {@code finsight.kafka.enabled} because it depends on the emitter, which is —
 * there is no point detecting a missing charge that cannot be reported anywhere.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class RecurringSweeper {

    private static final Logger log = LoggerFactory.getLogger(RecurringSweeper.class);

    /**
     * How late a charge may be before it is called missing. Billing dates slip over weekends and
     * card retries take a day or two; three days is long enough not to alert on either.
     */
    static final int GRACE_DAYS = 3;

    private final RecurringSeriesRepository repository;
    private final RiskDetectionEmitter emitter;
    private final int graceDays;

    public RecurringSweeper(RecurringSeriesRepository repository,
                            RiskDetectionEmitter emitter,
                            @Value("${finsight.recurring.grace-days:" + GRACE_DAYS + "}") int graceDays) {
        this.repository = repository;
        this.emitter = emitter;
        this.graceDays = graceDays;
    }

    /**
     * Runs hourly by default. The interval is resolution, not meaning: the window is measured in
     * days, so an hourly poll only decides how soon after midnight the alert lands.
     */
    @Scheduled(fixedDelayString = "${finsight.recurring.sweep-ms:3600000}",
            initialDelayString = "${finsight.recurring.sweep-initial-delay-ms:60000}")
    public void sweep() {
        sweep(LocalDate.now());
    }

    /** The sweep for a given day. Package-private so tests can pick the day. */
    @Transactional
    List<RecurringSeries> sweep(LocalDate today) {
        List<RecurringSeries> overdue = repository.findOverdue(
                RecurringDetector.CONFIRM_OCCURRENCES, today.minusDays(graceDays));
        if (overdue.isEmpty()) {
            return overdue;
        }
        Instant now = Instant.now();
        for (RecurringSeries series : overdue) {
            // Mark before emitting, for the same reason the digest scheduler stamps before
            // sending: the emit is best-effort, and a failure that left the row ACTIVE would
            // turn one broken send into the same alert every hour forever.
            series.lapse(now);
            repository.save(series);
            emitter.emit(series.getUserId(), series.getLastTransactionId(),
                    RiskRule.RECURRING_CHARGE_MISSED);
            log.info("Recurring series {} lapsed: userId={}, expected={}, lastSeen={}",
                    series.getId(), series.getUserId(), series.getNextExpected(), series.getLastSeen());
        }
        return overdue;
    }
}
