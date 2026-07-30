package com.pm.budgetservice.integration;

import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.repository.BudgetRepository;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end proof of the consumer half of the event flow: a {@code TransactionCreated}
 * record on the real topic (single-node KRaft broker, JSON without type headers —
 * exactly the producer's wire format) lands in {@code budgets.spent_amount} through the
 * listener, the idempotency inbox and the atomic SQL increment, against the real
 * Flyway-owned MySQL schema.
 *
 * <p>"Nothing happened" cannot be asserted directly on an async consumer, so the
 * ignore-rule tests send the non-matching events FIRST and a matching sentinel LAST on
 * the same partition: when the sentinel's effect is visible, the earlier events have
 * already been consumed, and the total proves they contributed nothing.
 */
class BudgetUtilizationConsumerIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final String TOPIC = "finsight.transactions.created";

    /** Distinct per test so the shared containers never leak budgets between tests. */
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(900_000L);

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
    static void stopKafka() {
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

    /** Prometheus naming: kafka_consumer_fetch_manager_records_lag{,_max} (consumer lag, P2-3). */
    private static final String LAG_METRIC_PREFIX = "kafka.consumer.fetch.manager.records.lag";

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private long uniqueUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    @Test
    void expenseEventIncrementsTheChosenBudget() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        sendExpense(userId, budgetId, "42.50", "USD", "2026-06-15");

        awaitSpentAmount(budgetId, "42.50");
    }

    @Test
    void expenseChargesOnlyTheChosenBudgetNotSiblingsInTheSameCategory() {
        long userId = uniqueUserId();
        // Two budgets on the SAME category — the case that used to double-count. The expense
        // names exactly one; only that one moves, the sibling stays at zero.
        UUID chosen = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");
        UUID sibling = createBudget(userId, 4L, BudgetPeriod.YEARLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "USD");

        sendExpense(userId, chosen, "100.00", "USD", "2026-06-15");
        // Sentinel against the sibling proves the consumer processed past the first expense.
        sendExpense(userId, sibling, "1.00", "USD", "2026-06-15");

        awaitSpentAmount(chosen, "100.00");
        awaitSpentAmount(sibling, "1.00");
    }

    @Test
    void nonMatchingEventsContributeNothing() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        // None of these may move the target budget's spent_amount:
        sendEvent(UUID.randomUUID(), userId, "INCOME", "500.00", "USD", budgetId, "2026-06-10"); // not an expense
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", "88.00", "USD", UUID.randomUUID(), "2026-06-10"); // other budget
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", "11.00", "USD", null, "2026-06-10");     // budget-less
        // Sentinel: consumed after all of the above (same partition, same key).
        sendExpense(userId, budgetId, "10.00", "USD", "2026-06-15");

        awaitSpentAmount(budgetId, "10.00");
    }

    @Test
    void duplicateEventIdIsCountedExactlyOnce() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        // The same event delivered twice (Kafka is at-least-once), then a distinct
        // sentinel so the await target proves the redelivery was skipped.
        UUID eventId = UUID.randomUUID();
        sendEvent(eventId, userId, "EXPENSE", "42.50", "USD", budgetId, "2026-06-15");
        sendEvent(eventId, userId, "EXPENSE", "42.50", "USD", budgetId, "2026-06-15");
        sendExpense(userId, budgetId, "7.50", "USD", "2026-06-16");

        awaitSpentAmount(budgetId, "50.00"); // 42.50 once + 7.50, not 92.50
    }

    @Test
    void consumerLagMetricIsExposedForPrometheus() {
        // The auto-configured consumer factory is instrumented by Boot's KafkaMetricsAutoConfiguration,
        // so the native lag metric is bound to Micrometer and exported at /actuator/prometheus (P2-3).
        // One expense forces the consumer to fetch so the lag sensors populate.
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");
        sendExpense(userId, budgetId, "1.00", "USD", "2026-06-15");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(meterRegistry.getMeters())
                        .extracting(Meter::getId)
                        .anyMatch(id -> id.getName().startsWith(LAG_METRIC_PREFIX)));
    }

    private UUID createBudget(long userId, long categoryId, BudgetPeriod period,
                              LocalDate start, LocalDate end, String currency) {
        Budget budget = budgetRepository.save(Budget.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .name("Test budget")
                .categoryId(categoryId)
                .periodType(period)
                .startDate(start)
                .endDate(end)
                .limitAmount(new BigDecimal("500.00"))
                .currency(currency)
                .build());
        return budget.getId();
    }

    private void sendExpense(long userId, UUID budgetId, String amount,
                             String currency, String transactionDate) {
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", amount, currency,
                budgetId, transactionDate);
    }

    /** Sends the producer's exact wire format: JSON keyed by userId, no type headers. */
    private void sendEvent(UUID eventId, long userId, String type, String amount,
                           String currency, UUID budgetId, String transactionDate) {
        String dateField = transactionDate == null ? "null" : "\"" + transactionDate + "\"";
        String budgetField = budgetId == null ? "null" : "\"" + budgetId + "\"";
        String json = """
                {"eventId":"%s","eventType":"TransactionCreated",
                 "occurredAt":"2026-06-12T10:00:00Z","transactionId":"%s",
                 "userId":%d,"type":"%s","amount":%s,"currency":"%s",
                 "categoryId":4,"transactionDate":%s,"walletId":7,"budgetId":%s}
                """.formatted(eventId, UUID.randomUUID(), userId, type, amount,
                currency, dateField, budgetField);
        producer.send(new ProducerRecord<>(TOPIC, String.valueOf(userId), json));
        producer.flush();
    }

    private void awaitSpentAmount(UUID budgetId, String expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(budgetRepository.findById(budgetId).orElseThrow().getSpentAmount())
                        .isEqualByComparingTo(expected));
    }
}
