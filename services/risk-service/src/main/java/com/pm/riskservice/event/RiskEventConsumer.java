package com.pm.riskservice.event;

import com.pm.riskservice.rule.RiskRule;
import com.pm.riskservice.rule.RiskRuleEngine;
import com.pm.riskservice.service.AnomalyService;
import com.pm.riskservice.service.InsightService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code TransactionCreated}, runs every risk rule via {@link RiskRuleEngine},
 * and hands each rule that fires to {@link RiskDetectionEmitter}, which persists the alert
 * (Phase D.2), publishes the {@code RiskDetected} event (Phase D.1) and counts the detection
 * (Phase D.3).
 *
 * <p>Thin by design: rule logic and observed-expense persistence live in the engine, the
 * emit path in the emitter; this class is the event plumbing. Gated by
 * {@code finsight.kafka.enabled} so test/local contexts without a broker never subscribe.
 *
 * <p>{@code finsight.risk.events.processed} counts every consumed event; the
 * {@code RiskDetected} side effects are best-effort (a publish failure is logged async),
 * the persisted alert and observed-expense rows being the durable record.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class RiskEventConsumer {

    private final RiskRuleEngine riskRuleEngine;
    private final RiskDetectionEmitter emitter;
    private final InsightService insightService;
    private final AnomalyService anomalyService;
    private final Counter processedEvents;

    public RiskEventConsumer(RiskRuleEngine riskRuleEngine,
                             RiskDetectionEmitter emitter,
                             InsightService insightService,
                             AnomalyService anomalyService,
                             MeterRegistry meterRegistry) {
        this.riskRuleEngine = riskRuleEngine;
        this.emitter = emitter;
        this.insightService = insightService;
        this.anomalyService = anomalyService;
        this.processedEvents = Counter.builder("finsight.risk.events.processed")
                .description("TransactionCreated events evaluated by the risk rules")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${finsight.kafka.topics.transaction-created}")
    public void onTransactionCreated(TransactionCreatedEvent event) {
        processedEvents.increment();

        for (RiskRule rule : riskRuleEngine.evaluate(event)) {
            emitter.emit(event.userId(), event.transactionId(), rule);
        }

        // The risk engine has recorded this expense; derive the behavioral insights (Phase E)
        // and anomalies (Phase F) from the now-updated observed_expenses. Both run regardless
        // of whether any risk fired. Insight evaluation also records INCOME (its own input).
        insightService.evaluate(event);
        anomalyService.evaluate(event);
    }
}
