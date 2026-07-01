package com.pm.transactionservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof that a successful create publishes a well-formed
 * {@code TransactionCreated} event to Kafka. Boots the full app (real security filter,
 * real MySQL from the shared singleton) against a real single-node KRaft broker, POSTs a
 * transaction through the API, and asserts the event lands on the topic with the right shape.
 *
 * <p>Re-enables admin topic auto-creation (off by default in the test profile) and points
 * {@code spring.kafka.bootstrap-servers} at the container via {@link DynamicPropertySource}.
 */
class TransactionEventPublishingIntegrationTest extends AbstractMockMvcIntegrationTest {

    private static final String TOPIC = "finsight.transactions.created";

    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        KAFKA.start();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Turn publishing on (the test profile defaults it off) and let the NewTopic bean
        // create the topic on startup for this test.
        registry.add("finsight.kafka.enabled", () -> "true");
        registry.add("spring.kafka.admin.auto-create", () -> "true");
        registry.add("finsight.kafka.topics.transaction-created", () -> TOPIC);
    }

    @Test
    void publishesTransactionCreatedEventAfterCommit() throws Exception {
        long userId = uniqueUserId();
        long wallet = createWallet(userId, "Cash", "CASH", "USD", "100.00");

        try (Consumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(TOPIC));
            // Ensure the consumer is assigned before we produce, so we don't miss the record.
            consumer.poll(Duration.ofMillis(500));

            String body = """
                    {"type":"EXPENSE","amount":42.50,"currency":"USD","categoryId":4,
                     "description":"Lunch","transactionDate":"2026-06-01","walletId":%d,
                     "metadata":{"merchant":"Cafe"}}
                    """.formatted(wallet);

            String response = mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            String transactionId = asJson(response).path("data").path("id").asText();

            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(15));

            // Keyed by userId so a user's events stay partition-ordered.
            assertThat(record.key()).isEqualTo(String.valueOf(userId));

            JsonNode event = asJson(record.value());
            assertThat(event.path("eventType").asText()).isEqualTo("TransactionCreated");
            assertThat(event.path("eventId").asText()).isNotBlank();
            assertThat(event.path("occurredAt").asText()).isNotBlank();
            assertThat(event.path("transactionId").asText()).isEqualTo(transactionId);
            assertThat(event.path("userId").asLong()).isEqualTo(userId);
            assertThat(event.path("type").asText()).isEqualTo("EXPENSE");
            assertThat(event.path("amount").asDouble()).isEqualTo(42.50);
            assertThat(event.path("currency").asText()).isEqualTo("USD");
            assertThat(event.path("categoryId").asLong()).isEqualTo(4);
            assertThat(event.path("transactionDate").asText()).isEqualTo("2026-06-01");
            assertThat(event.path("walletId").asLong()).isEqualTo(wallet);
        }
    }

    private Consumer<String, String> newConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "transaction-created-test");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        return new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
    }
}
