package com.pm.riskservice.event;

import com.pm.riskservice.logging.CorrelationIdFilter;
import com.pm.riskservice.rule.RiskRule;
import com.pm.riskservice.rule.RiskRuleEngine;
import com.pm.riskservice.service.AnomalyService;
import com.pm.riskservice.service.InsightService;
import com.pm.riskservice.service.RiskAlertService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the consumer's emit loop: it counts every consumed event as
 * {@code processed} and, for each {@link RiskRule} the engine returns, persists an alert,
 * publishes a {@code RiskDetected}, and increments the {@code detected} counter tagged by
 * type/severity. The rule logic itself is the engine's concern (see RiskRuleEngineTest).
 */
class RiskEventConsumerTest {

    private static final String RISK_TOPIC = "finsight.risk.detected";
    private static final String PROCESSED = "finsight.risk.events.processed";
    private static final String DETECTED = "finsight.risk.events.detected";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, RiskDetectedEvent> kafkaTemplate = mock(KafkaTemplate.class);
    private final RiskRuleEngine riskRuleEngine = mock(RiskRuleEngine.class);
    private final RiskAlertService riskAlertService = mock(RiskAlertService.class);
    private final InsightService insightService = mock(InsightService.class);
    private final AnomalyService anomalyService = mock(AnomalyService.class);

    private SimpleMeterRegistry meterRegistry;
    private RiskEventConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new RiskEventConsumer(kafkaTemplate, riskRuleEngine, riskAlertService,
                insightService, anomalyService, RISK_TOPIC, meterRegistry);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @SuppressWarnings("unchecked")
    private List<ProducerRecord<String, RiskDetectedEvent>> publishedRecords(int expected) {
        ArgumentCaptor<ProducerRecord<String, RiskDetectedEvent>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(expected)).send(captor.capture());
        return captor.getAllValues();
    }

    private static String correlationHeader(ProducerRecord<String, RiskDetectedEvent> record) {
        var header = record.headers().lastHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    void emitsOneRiskDetectedPerFiredRule() {
        UUID txId = UUID.randomUUID();
        when(riskRuleEngine.evaluate(any()))
                .thenReturn(List.of(RiskRule.RAPID_SPENDING, RiskRule.LARGE_DAILY_SPEND));

        consumer.onTransactionCreated(expense(txId, 42L));

        List<ProducerRecord<String, RiskDetectedEvent>> records = publishedRecords(2);
        assertThat(records).extracting(ProducerRecord::topic).containsOnly(RISK_TOPIC);
        assertThat(records).extracting(ProducerRecord::key).containsOnly("42");
        verify(riskAlertService, times(2)).record(any());

        List<RiskDetectedEvent> published = records.stream().map(ProducerRecord::value).toList();
        assertThat(published).extracting(RiskDetectedEvent::riskType)
                .containsExactly("RAPID_SPENDING", "LARGE_DAILY_SPEND");
        // Severity mapping is carried through from the rule.
        assertThat(published).extracting(RiskDetectedEvent::riskSeverity)
                .containsExactly("MEDIUM", "HIGH");
        assertThat(published).allSatisfy(e -> {
            assertThat(e.transactionId()).isEqualTo(txId);
            assertThat(e.userId()).isEqualTo(42L);
            assertThat(e.eventType()).isEqualTo("RiskDetected");
        });

        assertThat(count(PROCESSED)).isEqualTo(1.0);
        assertThat(detectedTagged("RAPID_SPENDING", "MEDIUM")).isEqualTo(1.0);
        assertThat(detectedTagged("LARGE_DAILY_SPEND", "HIGH")).isEqualTo(1.0);
    }

    @Test
    void highAmountFiresHighSeverity() {
        when(riskRuleEngine.evaluate(any())).thenReturn(List.of(RiskRule.HIGH_AMOUNT_EXPENSE));

        consumer.onTransactionCreated(expense(UUID.randomUUID(), 7L));

        ProducerRecord<String, RiskDetectedEvent> record = publishedRecords(1).get(0);
        assertThat(record.topic()).isEqualTo(RISK_TOPIC);
        assertThat(record.key()).isEqualTo("7");
        verify(riskAlertService).record(record.value());
        assertThat(record.value().riskType()).isEqualTo("HIGH_AMOUNT_EXPENSE");
        assertThat(record.value().riskSeverity()).isEqualTo("HIGH");
        assertThat(detectedTagged("HIGH_AMOUNT_EXPENSE", "HIGH")).isEqualTo(1.0);
    }

    @Test
    void noRulesMeansNoDetectionButStillProcessed() {
        when(riskRuleEngine.evaluate(any())).thenReturn(List.of());

        consumer.onTransactionCreated(expense(UUID.randomUUID(), 7L));

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(riskAlertService, never()).record(any());
        assertThat(count(PROCESSED)).isEqualTo(1.0);
        assertThat(detected()).isEqualTo(0.0);
    }

    @Test
    void carriesTheConsumedCorrelationIdOntoTheRiskEvent() {
        // The record interceptor put the incoming event's id in the MDC; passing it on is what keeps
        // notification-service's log lines in the same trace as the original HTTP write.
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-42");
        when(riskRuleEngine.evaluate(any())).thenReturn(List.of(RiskRule.HIGH_AMOUNT_EXPENSE));

        consumer.onTransactionCreated(expense(UUID.randomUUID(), 7L));

        assertThat(correlationHeader(publishedRecords(1).get(0))).isEqualTo("corr-42");
    }

    @Test
    void addsNoCorrelationHeaderWhenTheMdcHasNoId() {
        when(riskRuleEngine.evaluate(any())).thenReturn(List.of(RiskRule.HIGH_AMOUNT_EXPENSE));

        consumer.onTransactionCreated(expense(UUID.randomUUID(), 7L));

        assertThat(correlationHeader(publishedRecords(1).get(0))).isNull();
    }

    @Test
    void everyEventIsEvaluatedForInsightsRegardlessOfRisk() {
        when(riskRuleEngine.evaluate(any())).thenReturn(List.of());
        TransactionCreatedEvent event = expense(UUID.randomUUID(), 7L);

        consumer.onTransactionCreated(event);

        // Insight and anomaly evaluation run off the recorded expenses, independent of risk.
        verify(insightService).evaluate(event);
        verify(anomalyService).evaluate(event);
    }

    private TransactionCreatedEvent expense(UUID txId, long userId) {
        return new TransactionCreatedEvent(
                UUID.randomUUID(), "TransactionCreated", "2026-06-13T10:00:00Z",
                txId, userId, "EXPENSE", new BigDecimal("123.45"),
                "USD", 4L, "2026-06-13", 7L);
    }

    private double count(String name) {
        return meterRegistry.counter(name).count();
    }

    private double detectedTagged(String type, String severity) {
        return meterRegistry.counter(DETECTED, "type", type, "severity", severity).count();
    }

    private double detected() {
        return meterRegistry.find(DETECTED).counters().stream()
                .mapToDouble(Counter::count).sum();
    }
}
