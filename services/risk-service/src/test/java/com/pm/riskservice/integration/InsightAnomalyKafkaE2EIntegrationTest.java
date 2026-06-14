package com.pm.riskservice.integration;

import com.pm.riskservice.entity.Anomaly;
import com.pm.riskservice.entity.Insight;
import com.pm.riskservice.repository.AnomalyRepository;
import com.pm.riskservice.repository.InsightRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end proof that the behavioral-insight and anomaly pipelines run off the real Kafka
 * consumer flow (TC-1): {@code TransactionCreated} records on
 * {@code finsight.transactions.created} (the producer's exact headerless JSON wire format) are
 * consumed by {@code RiskEventConsumer}, which records them and drives {@code InsightService} and
 * {@code AnomalyService}. We then assert the generated {@code insights} and {@code anomalies} rows.
 *
 * <p>For one user, in one month: an INCOME of 1,000 then a run of small EXPENSEs takes monthly
 * spend past 80% of income (→ LOW_SAVINGS_RATE), and after ≥10 prior EXPENSEs a final 5× expense
 * is flagged (→ UNUSUAL_TRANSACTION_AMOUNT). Mirrors RiskDetectionIntegrationTest's broker setup.
 */
class InsightAnomalyKafkaE2EIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final String TX_TOPIC = "finsight.transactions.created";

    /** Prometheus naming: kafka_consumer_fetch_manager_records_lag{,_max} (consumer lag, P2-3). */
    private static final String LAG_METRIC_PREFIX = "kafka.consumer.fetch.manager.records.lag";

    @Autowired
    private InsightRepository insightRepository;
    @Autowired
    private AnomalyRepository anomalyRepository;
    @Autowired
    private MeterRegistry meterRegistry;

    /** Distinct per test so events never cross-contaminate on the shared broker. */
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(700_000L);

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
    void transactionCreatedFlowGeneratesInsightAndAnomaly() {
        long userId = USER_SEQUENCE.incrementAndGet();
        long categoryId = 4L;

        // 1) Income for the month so LOW_SAVINGS_RATE has a positive income side.
        send(userId, "INCOME", "1000", categoryId, "2026-06-01", "2026-06-01T08:00:00Z");

        // 2) Ten prior EXPENSEs of 100 (avg 100) — also pushes monthly spend (1,000) past 80%
        //    of income, so LOW_SAVINGS_RATE fires; and builds the ≥10-transaction anomaly baseline.
        for (int i = 1; i <= 10; i++) {
            send(userId, "EXPENSE", "100", categoryId,
                    "2026-06-10", String.format("2026-06-10T08:%02d:00Z", i));
        }

        // 3) An 11th EXPENSE of 500 = 5× the historical average → UNUSUAL_TRANSACTION_AMOUNT.
        send(userId, "EXPENSE", "500", categoryId, "2026-06-11", "2026-06-11T08:00:00Z");

        // Insight: LOW_SAVINGS_RATE generated for this user/month.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<Insight> insights = insightRepository.findAll().stream()
                    .filter(in -> in.getUserId().equals(userId))
                    .toList();
            assertThat(insights).extracting(Insight::getInsightType).contains("LOW_SAVINGS_RATE");
            assertThat(insights).filteredOn(in -> "LOW_SAVINGS_RATE".equals(in.getInsightType()))
                    .singleElement()
                    .satisfies(in -> assertThat(in.getPeriodMonth()).isEqualTo("2026-06"));
        });

        // Anomaly: the 5× expense was flagged.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<Anomaly> anomalies = anomalyRepository.findAll().stream()
                    .filter(a -> a.getUserId().equals(userId))
                    .toList();
            assertThat(anomalies).singleElement().satisfies(a -> {
                assertThat(a.getAnomalyType()).isEqualTo("UNUSUAL_TRANSACTION_AMOUNT");
                assertThat(a.getAmount()).isEqualByComparingTo("500");
                assertThat(a.getAverageAmount()).isEqualByComparingTo("100");
                assertThat(a.getRatio()).isEqualByComparingTo("5.00");
            });
        });
    }

    /**
     * P2-3: the native Kafka consumer-lag metric is bound to Micrometer (via the
     * MicrometerConsumerListener — auto-configured for the TransactionCreated group, attached
     * explicitly to the budget group) and therefore exported at {@code /actuator/prometheus}.
     * Sending one event forces the consumer to fetch so the lag sensors populate.
     */
    @Test
    void consumerLagMetricIsExposedForPrometheus() {
        long userId = USER_SEQUENCE.incrementAndGet();
        send(userId, "EXPENSE", "100", 4L, "2026-06-10", "2026-06-10T09:00:00Z");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(meterRegistry.getMeters())
                        .extracting(Meter::getId)
                        .anyMatch(id -> id.getName().startsWith(LAG_METRIC_PREFIX)));
    }

    /** Sends the producer's exact wire format: headerless JSON keyed by userId. */
    private void send(long userId, String type, String amount, long categoryId,
                      String transactionDate, String occurredAt) {
        String json = """
                {"eventId":"%s","eventType":"TransactionCreated",
                 "occurredAt":"%s","transactionId":"%s",
                 "userId":%d,"type":"%s","amount":%s,"currency":"USD",
                 "categoryId":%d,"transactionDate":"%s","walletId":7}
                """.formatted(UUID.randomUUID(), occurredAt, UUID.randomUUID(),
                userId, type, amount, categoryId, transactionDate);
        producer.send(new ProducerRecord<>(TX_TOPIC, String.valueOf(userId), json));
        producer.flush();
    }
}
