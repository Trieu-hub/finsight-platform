package com.pm.notificationservice.webhook;

import com.pm.notificationservice.delivery.DeliveryChannel;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.service.NotificationPreferenceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * POSTs alerts as JSON to a URL the user chose, signed so the receiver can tell it is really us.
 *
 * <p>Unlike web push this channel <b>does</b> carry the alert text. The destination is a system the
 * user nominated and controls, which is the whole point of a webhook — withholding the content
 * would leave them with a ping they cannot act on. Push withholds it because the destination there
 * is Google's or Mozilla's infrastructure, not the user's.
 *
 * <p>Off until the user supplies a URL and switches it on; there is no server-side enable flag.
 * Never throws: see {@link DeliveryChannel}.
 */
@Component
public class WebhookChannel implements DeliveryChannel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    private final NotificationPreferenceService preferences;
    private final WebhookUrlValidator urlValidator;
    private final WebhookSigner signer;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Counter sent;
    private final Counter failed;
    private final Counter blocked;

    public WebhookChannel(NotificationPreferenceService preferences,
                          WebhookUrlValidator urlValidator,
                          WebhookSigner signer,
                          ObjectMapper objectMapper,
                          WebhookProperties properties,
                          MeterRegistry meterRegistry) {
        this.preferences = preferences;
        this.urlValidator = urlValidator;
        this.signer = signer;
        this.objectMapper = objectMapper;
        Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
        // Redirects are refused rather than followed. A 302 is the cheap way to get us to connect
        // somewhere the URL validator never saw — the redirect target is not revalidated by the
        // HTTP client, so following one would hand back the SSRF the validator just closed.
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.sent = Counter.builder("finsight.notifications.webhook.sent")
                .description("Webhook calls the receiver accepted")
                .register(meterRegistry);
        this.failed = Counter.builder("finsight.notifications.webhook.failed")
                .description("Webhook calls that errored or were rejected by the receiver")
                .register(meterRegistry);
        this.blocked = Counter.builder("finsight.notifications.webhook.blocked")
                .description("Webhook calls not attempted because the stored URL failed validation")
                .register(meterRegistry);
    }

    @Override
    public boolean respectsDigest() {
        return true;
    }

    @Override
    public void deliver(Long userId, List<Notification> batch) {
        NotificationPreference preference = preferences.get(userId);
        if (!preference.isWebhookEnabled()
                || preference.getWebhookUrl() == null
                || preference.getWebhookSecret() == null) {
            return;
        }
        // Re-validated here and not only when it was saved: DNS behind the host can be repointed
        // at an internal address long after the URL passed the first check.
        if (!urlValidator.isAllowed(preference.getWebhookUrl())) {
            blocked.increment();
            return;
        }

        try {
            // Serialise once and sign exactly those bytes. Handing the object to RestClient and
            // signing a second serialisation would let the two drift over whitespace or field
            // order, and the receiver would see every signature as invalid.
            //
            // This is the application's own mapper (Spring Boot 4 / Jackson 3), so a timestamp is
            // rendered here exactly as the REST API renders the same row.
            String body = objectMapper.writeValueAsString(
                    WebhookPayload.of(batch, LocalDateTime.now()));
            String signature = signer.header(
                    preference.getWebhookSecret(), Instant.now().getEpochSecond(), body);

            restClient.post()
                    .uri(preference.getWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Vernfy-Signature", signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            sent.increment();
        } catch (RestClientResponseException e) {
            failed.increment();
            // A 3xx arrives here too, because redirects are not followed — worth seeing as a
            // failure rather than silently succeeding against an address nobody vetted.
            log.warn("Webhook for user {} returned {}", userId, e.getStatusCode().value());
        } catch (Exception e) {
            // Swallowed like every other channel: the notifications are already durable and in the
            // bell, and a dead receiver must not make the Kafka listener replay for everyone.
            failed.increment();
            log.warn("Webhook failed for user {}: {}", userId, e.toString());
        }
    }
}
