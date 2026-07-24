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
 * End-to-end proof of the reconciliation half of the flow (2026-07-03): a
 * {@code TransactionUpdated} reverses the old contribution and applies the new one, and a
 * {@code TransactionDeleted} reverses a contribution — both against the real single-node
 * KRaft broker (JSON without type headers, exactly the producer's wire format), the real
 * per-listener {@code spring.json.value.default.type} override, the idempotency inbox and
 * the atomic SQL increment, on the real Flyway-owned MySQL schema.
 *
 * <p>Each test first sends a {@code TransactionCreated} and awaits the resulting
 * {@code spent_amount} — that both seeds the value and proves the consumer is live —
 * before sending the update/delete, so the assertions never race the initial increment.
 */
class BudgetReversalConsumerIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final String CREATED_TOPIC = "finsight.transactions.created";
    private static final String UPDATED_TOPIC = "finsight.transactions.updated";
    private static final String DELETED_TOPIC = "finsight.transactions.deleted";

    /** Distinct per test so the shared containers never leak budgets between tests. */
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(950_000L);

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
        // The test profile defaults consumption off; this class needs the real listeners.
        registry.add("finsight.kafka.enabled", () -> "true");
    }

    @Autowired
    private BudgetRepository budgetRepository;

    private long uniqueUserId() {
        return USER_SEQUENCE.incrementAndGet();
    }

    @Test
    void deleteEventReversesSpentAmount() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        sendCreatedExpense(userId, budgetId, "100.00", "2026-06-15");
        awaitSpentAmount(budgetId, "100.00");

        sendDeleted(UUID.randomUUID(), userId, "EXPENSE", "100.00", budgetId);
        awaitSpentAmount(budgetId, "0.00");
    }

    @Test
    void updateEventReappliesWithTheNewAmount() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        sendCreatedExpense(userId, budgetId, "100.00", "2026-06-15");
        awaitSpentAmount(budgetId, "100.00");

        // Edit the amount 100 -> 60 on the same budget: reverse the old 100, apply the new 60.
        sendUpdated(userId,
                "EXPENSE", "100.00", budgetId,
                "EXPENSE", "60.00", budgetId);
        awaitSpentAmount(budgetId, "60.00");
    }

    @Test
    void updateEventMovesSpendBetweenBudgets() {
        long userId = uniqueUserId();
        UUID source = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");
        UUID destination = createBudget(userId, 5L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        sendCreatedExpense(userId, source, "100.00", "2026-06-15");
        awaitSpentAmount(source, "100.00");

        // Re-assign to a different budget: reverse from the source budget, apply to the destination.
        sendUpdated(userId,
                "EXPENSE", "100.00", source,
                "EXPENSE", "100.00", destination);
        awaitSpentAmount(source, "0.00");
        awaitSpentAmount(destination, "100.00");
    }

    @Test
    void duplicateDeleteIsAppliedExactlyOnce() {
        long userId = uniqueUserId();
        UUID budgetId = createBudget(userId, 4L, BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), "USD");

        sendCreatedExpense(userId, budgetId, "100.00", "2026-06-15");
        awaitSpentAmount(budgetId, "100.00");

        // The same delete delivered twice (Kafka is at-least-once): only one reversal must
        // land. A distinct +7 create sentinel makes the await target prove single reversal —
        // a double reversal would leave -93, never 7.
        UUID deleteEventId = UUID.randomUUID();
        sendDeleted(deleteEventId, userId, "EXPENSE", "100.00", budgetId);
        sendDeleted(deleteEventId, userId, "EXPENSE", "100.00", budgetId);
        sendCreatedExpense(userId, budgetId, "7.00", "2026-06-16");

        awaitSpentAmount(budgetId, "7.00");
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

    private void sendCreatedExpense(long userId, UUID budgetId, String amount,
                                    String transactionDate) {
        String json = """
                {"eventId":"%s","eventType":"TransactionCreated",
                 "occurredAt":"2026-06-12T10:00:00Z","transactionId":"%s",
                 "userId":%d,"type":"EXPENSE","amount":%s,"currency":"USD",
                 "categoryId":4,"transactionDate":"%s","walletId":7,"budgetId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), userId, amount,
                transactionDate, budgetId);
        send(CREATED_TOPIC, userId, json);
    }

    private void sendUpdated(long userId,
                             String oldType, String oldAmount, UUID oldBudgetId,
                             String newType, String newAmount, UUID newBudgetId) {
        String json = """
                {"eventId":"%s","eventType":"TransactionUpdated",
                 "occurredAt":"2026-06-12T10:00:00Z","transactionId":"%s","userId":%d,
                 "oldType":"%s","oldAmount":%s,"oldCurrency":"USD","oldCategoryId":4,"oldTransactionDate":"2026-06-15","oldBudgetId":"%s",
                 "newType":"%s","newAmount":%s,"newCurrency":"USD","newCategoryId":4,"newTransactionDate":"2026-06-15","newBudgetId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), userId,
                oldType, oldAmount, oldBudgetId,
                newType, newAmount, newBudgetId);
        send(UPDATED_TOPIC, userId, json);
    }

    private void sendDeleted(UUID eventId, long userId, String type, String amount, UUID budgetId) {
        String json = """
                {"eventId":"%s","eventType":"TransactionDeleted",
                 "occurredAt":"2026-06-12T10:00:00Z","transactionId":"%s","userId":%d,
                 "type":"%s","amount":%s,"currency":"USD","categoryId":4,"transactionDate":"2026-06-15","budgetId":"%s"}
                """.formatted(eventId, UUID.randomUUID(), userId, type, amount, budgetId);
        send(DELETED_TOPIC, userId, json);
    }

    /** Sends the producer's exact wire format: JSON keyed by userId, no type headers. */
    private void send(String topic, long userId, String json) {
        producer.send(new ProducerRecord<>(topic, String.valueOf(userId), json));
        producer.flush();
    }

    private void awaitSpentAmount(UUID budgetId, String expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(budgetRepository.findById(budgetId).orElseThrow().getSpentAmount())
                        .isEqualByComparingTo(expected));
    }
}
