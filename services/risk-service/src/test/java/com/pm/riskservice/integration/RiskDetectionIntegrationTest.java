package com.pm.riskservice.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import com.pm.riskservice.entity.RiskAlert;
import com.pm.riskservice.repository.RiskAlertRepository;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end proof of the risk flow against a real single-node KRaft broker: a
 * {@code TransactionCreated} record on {@code finsight.transactions.created} (JSON
 * without type headers — exactly the producer's wire format) is consumed, the
 * HIGH_AMOUNT_EXPENSE rule is evaluated, and a {@code RiskDetected} is published to
 * {@code finsight.risk.detected} only when the rule fires.
 *
 * <p>"Nothing happened" cannot be asserted directly on an async producer, so the
 * no-risk case sends the normal expense FIRST and a high-amount sentinel LAST on the
 * same partition (same user key): when the sentinel's RiskDetected arrives, the earlier
 * normal expense has already been consumed, and the fact that the only event received is
 * the sentinel's proves the normal expense produced nothing.
 *
 * <p>Extends the MySQL base because the service now persists each detection; the
 * high-amount case asserts both the published event and the persisted {@code risk_alerts}
 * row. The Kafka broker is this class's own container.
 */
class RiskDetectionIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final String TX_TOPIC = "finsight.transactions.created";
    private static final String RISK_TOPIC = "finsight.risk.detected";

    @Autowired
    private RiskAlertRepository riskAlertRepository;

    /** Distinct per test so events never cross-contaminate on the shared broker. */
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(500_000L);

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static KafkaProducer<String, String> producer;

    static {
        KAFKA.start();
    }

    @BeforeAll
    static void startProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
    }

    @AfterAll
    static void stop() {
        if (producer != null) {
            producer.close();
        }
        KAFKA.stop();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // The test profile defaults consumption off; this class needs the real listener.
        registry.add("finsight.kafka.enabled", () -> "true");
    }

    @Test
    void highAmountExpensePublishesRiskDetected() {
        long userId = uniqueUserId();
        UUID txId = UUID.randomUUID();

        sendTransaction(userId, txId, "EXPENSE", "10000000");

        try (KafkaConsumer<String, String> risk = riskConsumer(userId)) {
            String payload = awaitOneRiskEvent(risk, userId);
            assertThat(payload).contains("\"riskType\":\"HIGH_AMOUNT_EXPENSE\"");
            assertThat(payload).contains("\"riskSeverity\":\"HIGH\"");
            assertThat(payload).contains("\"eventType\":\"RiskDetected\"");
            assertThat(payload).contains("\"transactionId\":\"" + txId + "\"");
            assertThat(payload).contains("\"userId\":" + userId);
        }

        // The detection was also persisted to risk_alerts (durable record).
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<RiskAlert> alerts = riskAlertRepository.findAll().stream()
                    .filter(a -> a.getTransactionId().equals(txId))
                    .toList();
            assertThat(alerts).hasSize(1);
            RiskAlert alert = alerts.get(0);
            assertThat(alert.getUserId()).isEqualTo(userId);
            assertThat(alert.getRiskType()).isEqualTo("HIGH_AMOUNT_EXPENSE");
            assertThat(alert.getRiskSeverity()).isEqualTo("HIGH");
        });
    }

    @Test
    void normalExpenseProducesNoRiskEvent() {
        long userId = uniqueUserId();
        UUID normalTxId = UUID.randomUUID();
        UUID sentinelTxId = UUID.randomUUID();

        // Normal expense first (must NOT fire), then a high-amount sentinel on the same
        // user key (same partition) which MUST fire.
        sendTransaction(userId, normalTxId, "EXPENSE", "500.00");
        sendTransaction(userId, sentinelTxId, "EXPENSE", "10000000");

        try (KafkaConsumer<String, String> risk = riskConsumer(userId)) {
            String payload = awaitOneRiskEvent(risk, userId);
            // The only RiskDetected for this user is the sentinel's — the normal
            // expense contributed nothing.
            assertThat(payload).contains("\"transactionId\":\"" + sentinelTxId + "\"");
            assertThat(payload).doesNotContain(normalTxId.toString());
        }
    }

    @Test
    void rapidSpendingFiresOnFifthExpenseWithinWindow() {
        long userId = uniqueUserId();
        UUID fifthTxId = UUID.randomUUID();

        // Five small expenses (no single one is high-amount; total stays well under the
        // daily threshold) within the same 10-minute window — the fifth trips RAPID_SPENDING.
        for (int i = 1; i <= 4; i++) {
            sendTransaction(userId, UUID.randomUUID(), "EXPENSE", "100.00");
        }
        sendTransaction(userId, fifthTxId, "EXPENSE", "100.00");

        try (KafkaConsumer<String, String> risk = riskConsumer(userId)) {
            String payload = awaitOneRiskEvent(risk, userId);
            assertThat(payload).contains("\"riskType\":\"RAPID_SPENDING\"");
            assertThat(payload).contains("\"riskSeverity\":\"MEDIUM\"");
            assertThat(payload).contains("\"transactionId\":\"" + fifthTxId + "\"");
        }
    }

    @Test
    void largeDailySpendFiresWhenDailyTotalExceedsThreshold() {
        long userId = uniqueUserId();
        UUID crossingTxId = UUID.randomUUID();

        // Three 8,000,000 expenses on the same day: none is high-amount (< 10,000,000) and
        // three is below the rapid count, so only the third — which pushes the day total
        // from 16,000,000 to 24,000,000 (over 20,000,000) — trips LARGE_DAILY_SPEND.
        sendTransaction(userId, UUID.randomUUID(), "EXPENSE", "8000000");
        sendTransaction(userId, UUID.randomUUID(), "EXPENSE", "8000000");
        sendTransaction(userId, crossingTxId, "EXPENSE", "8000000");

        try (KafkaConsumer<String, String> risk = riskConsumer(userId)) {
            String payload = awaitOneRiskEvent(risk, userId);
            assertThat(payload).contains("\"riskType\":\"LARGE_DAILY_SPEND\"");
            assertThat(payload).contains("\"riskSeverity\":\"HIGH\"");
            assertThat(payload).contains("\"transactionId\":\"" + crossingTxId + "\"");
        }
    }

    private long uniqueUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    /** Sends the producer's exact wire format: JSON keyed by userId, no type headers. */
    private void sendTransaction(long userId, UUID transactionId, String type, String amount) {
        String json = """
                {"eventId":"%s","eventType":"TransactionCreated",
                 "occurredAt":"2026-06-13T10:00:00Z","transactionId":"%s",
                 "userId":%d,"type":"%s","amount":%s,"currency":"USD",
                 "categoryId":4,"transactionDate":"2026-06-13","walletId":7}
                """.formatted(UUID.randomUUID(), transactionId, userId, type, amount);
        producer.send(new ProducerRecord<>(TX_TOPIC, String.valueOf(userId), json));
        producer.flush();
    }

    /** A fresh group reading the risk topic from the beginning, filtered to this user's key. */
    private KafkaConsumer<String, String> riskConsumer(long userId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-risk-reader-" + userId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(RISK_TOPIC));
        return consumer;
    }

    /**
     * Polls until this user's RiskDetected appears, then returns its payload. Filtering
     * by the user key (events are keyed by userId) isolates this assertion from risk
     * events other tests leave on the shared topic.
     */
    private String awaitOneRiskEvent(KafkaConsumer<String, String> consumer, long userId) {
        String key = String.valueOf(userId);
        List<String> received = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    received.add(record.value());
                }
            }
            assertThat(received).isNotEmpty();
        });
        return received.get(0);
    }
}
