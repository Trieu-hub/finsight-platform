package com.pm.riskservice.event;

import com.pm.riskservice.rule.RiskRule;
import com.pm.riskservice.rule.RiskRuleEngine;
import com.pm.riskservice.service.InsightService;
import com.pm.riskservice.service.RiskAlertService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
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

    private SimpleMeterRegistry meterRegistry;
    private RiskEventConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new RiskEventConsumer(kafkaTemplate, riskRuleEngine, riskAlertService,
                insightService, RISK_TOPIC, meterRegistry);
    }

    @Test
    void emitsOneRiskDetectedPerFiredRule() {
        UUID txId = UUID.randomUUID();
        when(riskRuleEngine.evaluate(any()))
                .thenReturn(List.of(RiskRule.RAPID_SPENDING, RiskRule.LARGE_DAILY_SPEND));

        consumer.onTransactionCreated(expense(txId, 42L));

        ArgumentCaptor<RiskDetectedEvent> captor = ArgumentCaptor.forClass(RiskDetectedEvent.class);
        verify(kafkaTemplate, times(2)).send(eq(RISK_TOPIC), eq("42"), captor.capture());
        verify(riskAlertService, times(2)).record(any());

        List<RiskDetectedEvent> published = captor.getAllValues();
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

        ArgumentCaptor<RiskDetectedEvent> captor = ArgumentCaptor.forClass(RiskDetectedEvent.class);
        verify(kafkaTemplate).send(eq(RISK_TOPIC), eq("7"), captor.capture());
        verify(riskAlertService).record(captor.getValue());
        assertThat(captor.getValue().riskType()).isEqualTo("HIGH_AMOUNT_EXPENSE");
        assertThat(captor.getValue().riskSeverity()).isEqualTo("HIGH");
        assertThat(detectedTagged("HIGH_AMOUNT_EXPENSE", "HIGH")).isEqualTo(1.0);
    }

    @Test
    void noRulesMeansNoDetectionButStillProcessed() {
        when(riskRuleEngine.evaluate(any())).thenReturn(List.of());

        consumer.onTransactionCreated(expense(UUID.randomUUID(), 7L));

        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(riskAlertService, never()).record(any());
        assertThat(count(PROCESSED)).isEqualTo(1.0);
        assertThat(detected()).isEqualTo(0.0);
    }

    @Test
    void everyEventIsEvaluatedForInsightsRegardlessOfRisk() {
        when(riskRuleEngine.evaluate(any())).thenReturn(List.of());
        TransactionCreatedEvent event = expense(UUID.randomUUID(), 7L);

        consumer.onTransactionCreated(event);

        // Insight evaluation runs off the recorded expenses, independent of risk detection.
        verify(insightService).evaluate(event);
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
