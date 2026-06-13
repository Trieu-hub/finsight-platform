package com.pm.riskservice.event;

import com.pm.riskservice.rule.RiskRule;
import com.pm.riskservice.rule.RiskRuleEngine;
import com.pm.riskservice.service.InsightService;
import com.pm.riskservice.service.RiskAlertService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code TransactionCreated}, runs every risk rule via {@link RiskRuleEngine},
 * and for each rule that fires emits one {@code RiskDetected}: it persists the alert to
 * {@code risk_alerts} (durable record, Phase D.2), publishes the event to the risk topic
 * keyed by {@code userId} (best-effort notification), and increments the detection counter
 * tagged by {@code type}/{@code severity} (Phase D.3).
 *
 * <p>Thin by design: rule logic and observed-expense persistence live in the engine; this
 * class is the event plumbing. Gated by {@code finsight.kafka.enabled} so test/local
 * contexts without a broker never subscribe.
 *
 * <p>{@code finsight.risk.events.processed} counts every consumed event; the
 * {@code RiskDetected} side effects are best-effort (a publish failure is logged async),
 * the persisted alert and observed-expense rows being the durable record.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class RiskEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RiskEventConsumer.class);

    static final String DETECTED_COUNTER = "finsight.risk.events.detected";

    private final KafkaTemplate<String, RiskDetectedEvent> kafkaTemplate;
    private final RiskRuleEngine riskRuleEngine;
    private final RiskAlertService riskAlertService;
    private final InsightService insightService;
    private final MeterRegistry meterRegistry;
    private final String riskTopic;
    private final Counter processedEvents;

    public RiskEventConsumer(KafkaTemplate<String, RiskDetectedEvent> kafkaTemplate,
                             RiskRuleEngine riskRuleEngine,
                             RiskAlertService riskAlertService,
                             InsightService insightService,
                             @Value("${finsight.kafka.topics.risk-detected}") String riskTopic,
                             MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.riskRuleEngine = riskRuleEngine;
        this.riskAlertService = riskAlertService;
        this.insightService = insightService;
        this.meterRegistry = meterRegistry;
        this.riskTopic = riskTopic;
        this.processedEvents = Counter.builder("finsight.risk.events.processed")
                .description("TransactionCreated events evaluated by the risk rules")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "${finsight.kafka.topics.transaction-created}")
    public void onTransactionCreated(TransactionCreatedEvent event) {
        processedEvents.increment();

        for (RiskRule rule : riskRuleEngine.evaluate(event)) {
            emit(event, rule);
        }

        // The risk engine has recorded this expense; derive the behavioral insight from the
        // now-updated observed_expenses (Phase E.1). Independent of whether any risk fired.
        insightService.evaluate(event);
    }

    private void emit(TransactionCreatedEvent event, RiskRule rule) {
        RiskDetectedEvent risk = RiskDetectedEvent.of(
                event.userId(), event.transactionId(), rule.name(), rule.severity());
        // Persist first (durable record), then publish (best-effort notification).
        riskAlertService.record(risk);
        kafkaTemplate.send(riskTopic, String.valueOf(event.userId()), risk);
        // Tagged by type/severity so the Risk dashboard breaks detections down by each.
        meterRegistry.counter(DETECTED_COUNTER, "type", rule.name(), "severity", rule.severity())
                .increment();
        log.info("Risk detected [{}/{}]: transactionId={}, userId={}, amount={}",
                rule.name(), rule.severity(), event.transactionId(), event.userId(), event.amount());
    }
}
