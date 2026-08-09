package com.pm.riskservice.event;

import com.pm.riskservice.logging.CorrelationIdFilter;
import com.pm.riskservice.rule.RiskRule;
import com.pm.riskservice.service.RiskAlertService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns a fired {@link RiskRule} into a {@code RiskDetected}: persists the alert to
 * {@code risk_alerts} (the durable record), publishes to the risk topic keyed by
 * {@code userId} (best-effort notification), and increments the detection counter tagged by
 * {@code type}/{@code severity}.
 *
 * <p>Extracted from {@code RiskEventConsumer} when the recurring sweep (Phase G.1) became a
 * second source of detections. The sweep has no consumed record to react to, so the emit path
 * had to stop being part of the consumer; nothing about the emitted event changed.
 *
 * <p>Gated by {@code finsight.kafka.enabled} for the same reason the consumer is: a test or
 * local context without a broker must not need one.
 */
@Component
@ConditionalOnProperty(name = "finsight.kafka.enabled", havingValue = "true")
public class RiskDetectionEmitter {

    private static final Logger log = LoggerFactory.getLogger(RiskDetectionEmitter.class);

    static final String DETECTED_COUNTER = "finsight.risk.events.detected";

    private final KafkaTemplate<String, RiskDetectedEvent> kafkaTemplate;
    private final RiskAlertService riskAlertService;
    private final MeterRegistry meterRegistry;
    private final String riskTopic;

    public RiskDetectionEmitter(KafkaTemplate<String, RiskDetectedEvent> kafkaTemplate,
                                RiskAlertService riskAlertService,
                                @Value("${finsight.kafka.topics.risk-detected}") String riskTopic,
                                MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.riskAlertService = riskAlertService;
        this.riskTopic = riskTopic;
        this.meterRegistry = meterRegistry;
    }

    /** Records and publishes one detection for {@code rule}. */
    public void emit(Long userId, UUID transactionId, RiskRule rule) {
        RiskDetectedEvent risk = RiskDetectedEvent.of(
                userId, transactionId, rule.name(), rule.severity());
        // Persist first (durable record), then publish (best-effort notification).
        riskAlertService.record(risk);
        kafkaTemplate.send(toRecord(String.valueOf(userId), risk));
        // Tagged by type/severity so the Risk dashboard breaks detections down by each.
        meterRegistry.counter(DETECTED_COUNTER, "type", rule.name(), "severity", rule.severity())
                .increment();
        log.info("Risk detected [{}/{}]: transactionId={}, userId={}",
                rule.name(), rule.severity(), transactionId, userId);
    }

    /**
     * The record to publish, keyed by {@code userId}, carrying whatever correlation id is in the
     * MDC as a {@value CorrelationIdFilter#CORRELATION_ID_HEADER} header. On the consumer path
     * the record interceptor put the triggering event's id there, so the whole chain — HTTP
     * write → transaction → risk → notification — stays one searchable trace. On the sweep path
     * the MDC is empty and no header is added; there was no request to correlate with.
     */
    ProducerRecord<String, RiskDetectedEvent> toRecord(String key, RiskDetectedEvent risk) {
        ProducerRecord<String, RiskDetectedEvent> record = new ProducerRecord<>(riskTopic, key, risk);
        String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            record.headers().add(CorrelationIdFilter.CORRELATION_ID_HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
