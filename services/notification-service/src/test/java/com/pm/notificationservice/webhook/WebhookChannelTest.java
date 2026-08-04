package com.pm.notificationservice.webhook;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.service.NotificationPreferenceService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the webhook over real HTTP against a JDK {@link HttpServer}, the same way
 * {@code LlmAlertNarratorTest} does — no test dependency, no external network.
 *
 * <p>The URL validator is stubbed to allow, because the server necessarily binds to loopback,
 * which the real validator refuses. That refusal is what {@link WebhookUrlValidatorTest} covers;
 * here the interest is what goes over the wire once a URL has been accepted.
 */
class WebhookChannelTest {

    // Jackson 3 (tools.jackson), the mapper Spring Boot 4 autoconfigures and injects in production.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private NotificationPreferenceService preferences;
    private WebhookUrlValidator urlValidator;
    private HttpServer server;

    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedSignature = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        preferences = mock(NotificationPreferenceService.class);
        urlValidator = mock(WebhookUrlValidator.class);
        when(urlValidator.isAllowed(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsEveryAlertInTheBatchAsOneSignedCall() throws Exception {
        String url = startServer(200);
        when(preferences.get(7L)).thenReturn(preference(url, true, "whsec_test"));

        channel().deliver(7L, List.of(notification("Large expense"), notification("Budget exceeded")));

        assertThat(requestCount.get()).isEqualTo(1);
        JsonNode body = objectMapper.readTree(receivedBody.get());
        assertThat(body.get("count").asInt()).isEqualTo(2);
        assertThat(body.get("alerts")).hasSize(2);
        assertThat(body.get("alerts").get(0).get("title").asText()).isEqualTo("Large expense");
        assertThat(body.get("alerts").get(0).get("severity").asText()).isEqualTo("HIGH");
    }

    @Test
    void signsExactlyTheBytesItSent() throws Exception {
        // The signature has to cover the serialised body byte for byte. Serialising once for the
        // MAC and again for the request would let the two drift and every receiver would reject us.
        String url = startServer(200);
        when(preferences.get(7L)).thenReturn(preference(url, true, "whsec_test"));

        channel().deliver(7L, List.of(notification("Large expense")));

        String header = receivedSignature.get();
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        long timestamp = Long.parseLong(header.substring(2, header.indexOf(',')));
        assertThat(header).isEqualTo(
                new WebhookSigner().header("whsec_test", timestamp, receivedBody.get()));
    }

    @Test
    void wrapsASingleAlertInTheSameArrayShapeAsABatch() throws Exception {
        // A receiver must not have to handle two payload shapes; switching the user to an hourly
        // digest would otherwise break their integration silently.
        String url = startServer(200);
        when(preferences.get(7L)).thenReturn(preference(url, true, "whsec_test"));

        channel().deliver(7L, List.of(notification("Large expense")));

        JsonNode body = objectMapper.readTree(receivedBody.get());
        assertThat(body.get("count").asInt()).isEqualTo(1);
        assertThat(body.get("alerts").isArray()).isTrue();
    }

    @Test
    void staysSilentWhenTheUserHasNoWebhook() throws Exception {
        String url = startServer(200);
        when(preferences.get(7L)).thenReturn(preference(url, false, "whsec_test"));

        channel().deliver(7L, List.of(notification("Large expense")));

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void staysSilentWhenTheStoredUrlNoLongerValidates() throws Exception {
        // DNS behind a host that passed at save time can be repointed at an internal address.
        String url = startServer(200);
        when(preferences.get(7L)).thenReturn(preference(url, true, "whsec_test"));
        when(urlValidator.isAllowed(any())).thenReturn(false);

        channel().deliver(7L, List.of(notification("Large expense")));

        assertThat(requestCount.get()).isZero();
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        // A 302 is the cheap way to get us to connect somewhere the validator never saw. The
        // redirect must surface as a failed delivery, not as a second request.
        String url = startServer(302);
        when(preferences.get(7L)).thenReturn(preference(url, true, "whsec_test"));

        channel().deliver(7L, List.of(notification("Large expense")));

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void swallowsReceiverFailuresSoTheKafkaListenerNeverReplays() throws Exception {
        String url = startServer(500);
        when(preferences.get(7L)).thenReturn(preference(url, true, "whsec_test"));

        assertThatCode(() -> channel().deliver(7L, List.of(notification("Large expense"))))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsAnUnreachableReceiver() {
        when(preferences.get(7L)).thenReturn(
                preference("https://webhook.invalid./hook", true, "whsec_test"));

        assertThatCode(() -> channel().deliver(7L, List.of(notification("Large expense"))))
                .doesNotThrowAnyException();
    }

    private WebhookChannel channel() {
        WebhookProperties properties = new WebhookProperties();
        properties.setTimeoutMs(2000);
        return new WebhookChannel(preferences, urlValidator, new WebhookSigner(), objectMapper,
                properties, new SimpleMeterRegistry());
    }

    private String startServer(int status) throws IOException {
        configuredStatus = status;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        receivedSignature.set(exchange.getRequestHeaders().getFirst("X-Vernfy-Signature"));
        if (configuredStatus == 302) {
            // Points at a closed port: if the client ever did follow it, the request count would
            // stay at 1 but the call would fail differently — so the assertion is on the count.
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
        }
        exchange.sendResponseHeaders(configuredStatus, -1);
        exchange.close();
    }

    private int configuredStatus = 200;

    private static NotificationPreference preference(String url, boolean enabled, String secret) {
        return NotificationPreference.builder()
                .userId(7L)
                .webhookUrl(url)
                .webhookEnabled(enabled)
                .webhookSecret(secret)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static Notification notification(String title) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(7L)
                .type("RISK_ALERT")
                .severity("HIGH")
                .title(title)
                .message("Something happened.")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
