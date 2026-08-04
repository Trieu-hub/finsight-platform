package com.pm.notificationservice.push;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.PushSubscription;
import com.pm.notificationservice.repository.PushSubscriptionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebPushChannelTest {

    private PushSubscriptionRepository repository;
    private PushProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(PushSubscriptionRepository.class);
        properties = new PushProperties();
    }

    @Test
    void doesNothingWithoutAVapidKeypair() {
        // The default state of a fresh checkout. It must not even look up subscriptions, let alone
        // try to sign something with an empty key.
        WebPushChannel channel = new WebPushChannel(
                repository, properties, new VapidSigner(properties), new SimpleMeterRegistry());

        channel.deliver(7L, List.of(notification(7L)));

        verify(repository, never()).findByUserId(any());
    }

    @Test
    void skipsUsersWithNoSubscribedBrowser() {
        configureKeys();
        when(repository.findByUserId(7L)).thenReturn(List.of());
        WebPushChannel channel = new WebPushChannel(
                repository, properties, new VapidSigner(properties), new SimpleMeterRegistry());

        channel.deliver(7L, List.of(notification(7L)));

        verify(repository, never()).delete(any());
    }

    @Test
    void keepsTheSubscriptionWhenThePushServiceIsSimplyUnreachable() {
        // An endpoint that cannot resolve stands in for the push service being down. That is a
        // transient failure, and dropping the row would silently unsubscribe the user for good —
        // only an explicit 404/410 means "gone", and this asserts the distinction.
        configureKeys();
        PushSubscription subscription = subscription("https://push.invalid./unreachable");
        when(repository.findByUserId(7L)).thenReturn(List.of(subscription));
        WebPushChannel channel = new WebPushChannel(
                repository, properties, new VapidSigner(properties), new SimpleMeterRegistry());

        channel.deliver(7L, List.of(notification(7L)));

        verify(repository, never()).delete(any());
    }

    private void configureKeys() {
        // Must be a real 32-byte scalar: with anything shorter the signer throws before a request
        // is ever attempted, and the unreachable-endpoint test below would pass without touching
        // the code path it claims to cover.
        byte[] scalar = new byte[32];
        scalar[31] = 42;
        properties.setPrivateKey(Base64.getUrlEncoder().withoutPadding().encodeToString(scalar));
        properties.setPublicKey("BOrHjt9H1wFS");
    }

    private static PushSubscription subscription(String endpoint) {
        return PushSubscription.builder()
                .id(UUID.randomUUID())
                .userId(7L)
                .endpoint(endpoint)
                .p256dh("p")
                .authSecret("a")
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static Notification notification(Long userId) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .type("RISK_ALERT")
                .severity("HIGH")
                .title("Large expense")
                .message("You spent a lot.")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
