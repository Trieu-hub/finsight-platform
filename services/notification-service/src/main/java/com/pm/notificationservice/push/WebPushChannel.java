package com.pm.notificationservice.push;

import com.pm.notificationservice.delivery.DeliveryChannel;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.PushSubscription;
import com.pm.notificationservice.repository.PushSubscriptionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;

/**
 * Sends a <b>payload-free</b> push to every browser the user has subscribed, so a notification
 * reaches them with the tab closed.
 *
 * <p>Payload-free is a deliberate choice, not a shortcut. The push carries no body at all; the
 * service worker reacts by fetching the unread notifications over the normal authenticated API.
 * That means the alert text — which is financial information — never passes through Google's or
 * Mozilla's push infrastructure, and this service needs none of the RFC 8291 content encryption.
 *
 * <p>Off until a VAPID keypair is configured. Never throws: see {@link DeliveryChannel}.
 */
@Component
public class WebPushChannel implements DeliveryChannel {

    private static final Logger log = LoggerFactory.getLogger(WebPushChannel.class);

    private final PushSubscriptionRepository subscriptions;
    private final PushProperties properties;
    private final VapidSigner signer;
    private final RestClient restClient;
    private final Counter sent;
    private final Counter expired;
    private final Counter failed;

    public WebPushChannel(PushSubscriptionRepository subscriptions,
                          PushProperties properties,
                          VapidSigner signer,
                          MeterRegistry meterRegistry) {
        this.subscriptions = subscriptions;
        this.properties = properties;
        this.signer = signer;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.sent = Counter.builder("finsight.notifications.push.sent")
                .description("Web push messages accepted by the browser's push service")
                .register(meterRegistry);
        this.expired = Counter.builder("finsight.notifications.push.expired")
                .description("Push subscriptions dropped after the push service reported them gone")
                .register(meterRegistry);
        this.failed = Counter.builder("finsight.notifications.push.failed")
                .description("Web push attempts that failed for a reason other than expiry")
                .register(meterRegistry);
    }

    @Override
    public void deliver(Notification notification) {
        if (!properties.isConfigured()) {
            return;
        }
        List<PushSubscription> targets = subscriptions.findByUserId(notification.getUserId());
        for (PushSubscription subscription : targets) {
            push(subscription);
        }
    }

    private void push(PushSubscription subscription) {
        try {
            restClient.post()
                    .uri(subscription.getEndpoint())
                    .header("Authorization", signer.authorizationHeader(subscription.getEndpoint()))
                    .header("TTL", String.valueOf(properties.getTtlSeconds()))
                    // No Content-Encoding: that header is what tells a push service to expect an
                    // encrypted body, and there is none.
                    .retrieve()
                    .toBodilessEntity();
            sent.increment();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // 404/410 is the push service saying this browser is gone for good (permission
            // revoked, profile wiped, subscription renewed). Keeping the row would mean retrying
            // it forever, so the subscription is dropped here rather than by a cleanup job.
            if (status == 404 || status == 410) {
                subscriptions.delete(subscription);
                expired.increment();
                log.debug("Dropped expired push subscription for user {}", subscription.getUserId());
            } else {
                failed.increment();
                // The body is where a push service says *why*. Dropping it once turned a one-line
                // VAPID bug ("invalid aud claim") into a hunt across the keypair and the endpoint.
                log.warn("Web push rejected with {} for user {}: {}", status,
                        subscription.getUserId(), e.getResponseBodyAsString());
            }
        } catch (Exception e) {
            failed.increment();
            log.warn("Web push failed for user {}: {}", subscription.getUserId(), e.toString());
        }
    }
}
