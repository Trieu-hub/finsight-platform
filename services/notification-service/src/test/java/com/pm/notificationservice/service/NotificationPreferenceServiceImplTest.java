package com.pm.notificationservice.service;

import com.pm.notificationservice.entity.DigestMode;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.exception.InvalidWebhookUrlException;
import com.pm.notificationservice.repository.NotificationPreferenceRepository;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.service.impl.NotificationPreferenceServiceImpl;
import com.pm.notificationservice.webhook.WebhookUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationPreferenceServiceImplTest {

    private NotificationPreferenceRepository repository;
    private NotificationRepository notifications;
    private WebhookUrlValidator urlValidator;
    private NotificationPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationPreferenceRepository.class);
        notifications = mock(NotificationRepository.class);
        urlValidator = mock(WebhookUrlValidator.class);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new NotificationPreferenceServiceImpl(repository, notifications, urlValidator);
    }

    @Test
    void defaultsToImmediateForAUserWhoNeverChose() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.get(7L).getDigestMode()).isEqualTo(DigestMode.IMMEDIATE);
    }

    @Test
    void mintsASecretWhenAWebhookUrlIsFirstSet() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        String secret = service.setWebhook(7L, "https://example.test/hook", true);

        assertThat(secret).startsWith("whsec_");
    }

    @Test
    void keepsTheSecretWhenOnlyTheSwitchMoves() {
        // The receiver already has this key configured. Rotating it on a toggle would silently
        // break an integration that the user only meant to pause.
        NotificationPreference existing = stored("https://example.test/hook", "whsec_existing");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        String secret = service.setWebhook(7L, "https://example.test/hook", false);

        assertThat(secret).isNull();
        assertThat(existing.getWebhookSecret()).isEqualTo("whsec_existing");
    }

    @Test
    void mintsAFreshSecretWhenTheDestinationChanges() {
        // A key that signed for the old URL must not keep verifying for a different one.
        NotificationPreference existing = stored("https://old.test/hook", "whsec_existing");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        String secret = service.setWebhook(7L, "https://new.test/hook", true);

        assertThat(secret).isNotNull().isNotEqualTo("whsec_existing");
    }

    @Test
    void clearingTheUrlClearsTheSecretAndTheSwitch() {
        NotificationPreference existing = stored("https://example.test/hook", "whsec_existing");
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        service.setWebhook(7L, null, true);

        assertThat(existing.getWebhookUrl()).isNull();
        assertThat(existing.getWebhookSecret()).isNull();
        assertThat(existing.isWebhookEnabled()).isFalse();
    }

    @Test
    void refusesAUrlTheValidatorRejects() {
        when(repository.findById(7L)).thenReturn(Optional.empty());
        doThrow(new InvalidWebhookUrlException("Webhook URL must use https"))
                .when(urlValidator).validate("http://example.test/hook");

        assertThatThrownBy(() -> service.setWebhook(7L, "http://example.test/hook", true))
                .isInstanceOf(InvalidWebhookUrlException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void changingDigestModeStartsAFreshWindow() {
        // Whatever is pending was queued under the old setting. Carrying it across would either
        // strand it (going to IMMEDIATE, where no scheduler flushes) or resend already-delivered
        // alerts (coming from IMMEDIATE).
        NotificationPreference existing = stored("https://example.test/hook", "whsec_existing");
        existing.setDigestMode(DigestMode.IMMEDIATE);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(notifications.findByUserIdAndDigestedAtIsNullOrderByCreatedAtAsc(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(notification()));

        service.setDigestMode(7L, DigestMode.DAILY);

        verify(notifications).markDigested(anyCollection(), any());
        assertThat(existing.getDigestMode()).isEqualTo(DigestMode.DAILY);
    }

    @Test
    void reSelectingTheSameModeTouchesNothingPending() {
        NotificationPreference existing = stored("https://example.test/hook", "whsec_existing");
        existing.setDigestMode(DigestMode.HOURLY);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));

        service.setDigestMode(7L, DigestMode.HOURLY);

        verify(notifications, never()).markDigested(anyCollection(), any());
    }

    private static NotificationPreference stored(String url, String secret) {
        return NotificationPreference.builder()
                .userId(7L)
                .webhookUrl(url)
                .webhookSecret(secret)
                .webhookEnabled(true)
                .digestMode(DigestMode.IMMEDIATE)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static Notification notification() {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(7L)
                .type("RISK_ALERT")
                .severity("HIGH")
                .title("Large expense")
                .message("You spent a lot.")
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
