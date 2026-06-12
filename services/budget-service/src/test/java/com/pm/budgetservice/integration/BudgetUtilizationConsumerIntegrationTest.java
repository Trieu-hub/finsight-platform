package com.pm.budgetservice.integration;

import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.repository.BudgetRepository;
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

    @Autowired
    private BudgetRepository budgetRepository;

    private long uniqueUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    @Test
    void expenseEventIncrementsMatchingBudget() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        sendExpense(userId, 4L, "42.50", "USD", "2026-06-15");

        awaitSpentAmount(budgetId, "42.50");
    }

    @Test
    void expenseEventIncrementsAllOverlappingBudgets() {
        long userId = uniqueUserId();
        // A MONTHLY and a YEARLY budget for the same category legitimately coexist
        // (the duplicate guard keys on periodType + startDate); one expense inside
        // both windows must count against both.
        UUID monthly = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");
        UUID yearly = createBudget(userId, 4L, BudgetPeriod.YEARLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "USD");

        sendExpense(userId, 4L, "100.00", "USD", "2026-06-15");

        awaitSpentAmount(monthly, "100.00");
        awaitSpentAmount(yearly, "100.00");
    }

    @Test
    void nonMatchingEventsContributeNothing() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        // None of these may move spent_amount:
        sendEvent(UUID.randomUUID(), userId, "INCOME", "500.00", "USD", 4L, "2026-06-10");
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", "77.00", "EUR", 4L, "2026-06-10");  // currency mismatch
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", "88.00", "USD", 9L, "2026-06-10");  // other category
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", "99.00", "USD", 4L, "2026-05-10");  // outside window
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", "11.00", "USD", 4L, null);          // no date
        // Sentinel: consumed after all of the above (same partition, same key).
        sendExpense(userId, 4L, "10.00", "USD", "2026-06-15");

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
        sendEvent(eventId, userId, "EXPENSE", "42.50", "USD", 4L, "2026-06-15");
        sendEvent(eventId, userId, "EXPENSE", "42.50", "USD", 4L, "2026-06-15");
        sendExpense(userId, 4L, "7.50", "USD", "2026-06-16");

        awaitSpentAmount(budgetId, "50.00"); // 42.50 once + 7.50, not 92.50
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

    private void sendExpense(long userId, long categoryId, String amount,
                             String currency, String transactionDate) {
        sendEvent(UUID.randomUUID(), userId, "EXPENSE", amount, currency,
                categoryId, transactionDate);
    }

    /** Sends the producer's exact wire format: JSON keyed by userId, no type headers. */
    private void sendEvent(UUID eventId, long userId, String type, String amount,
                           String currency, Long categoryId, String transactionDate) {
        String dateField = transactionDate == null ? "null" : "\"" + transactionDate + "\"";
        String json = """
                {"eventId":"%s","eventType":"TransactionCreated",
                 "occurredAt":"2026-06-12T10:00:00Z","transactionId":"%s",
                 "userId":%d,"type":"%s","amount":%s,"currency":"%s",
                 "categoryId":%d,"transactionDate":%s,"walletId":7}
                """.formatted(eventId, UUID.randomUUID(), userId, type, amount,
                currency, categoryId, dateField);
        producer.send(new ProducerRecord<>(TOPIC, String.valueOf(userId), json));
        producer.flush();
    }

    private void awaitSpentAmount(UUID budgetId, String expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(budgetRepository.findById(budgetId).orElseThrow().getSpentAmount())
                        .isEqualByComparingTo(expected));
    }
}
