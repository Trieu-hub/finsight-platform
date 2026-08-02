package com.pm.notificationservice.email;

import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.service.NotificationPreferenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailChannelTest {

    private JavaMailSender sender;
    private NotificationPreferenceService preferences;
    private EmailProperties properties;

    @BeforeEach
    void setUp() {
        sender = mock(JavaMailSender.class);
        preferences = mock(NotificationPreferenceService.class);
        properties = new EmailProperties();
    }

    @Test
    void sendsToTheStoredAddressWhenTheUserOptedIn() {
        when(preferences.get(7L)).thenReturn(preference(true, "user@example.com"));

        channel(sender).deliver(notification());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).isEqualTo("Large expense");
        // The alert text is the narrator's, unchanged, so the wording matches the in-app copy.
        assertThat(message.getText()).startsWith("You spent a lot.");
    }

    @Test
    void staysSilentWhenNoMailServerIsConfigured() {
        // No JavaMailSender bean is the default: spring.mail.host unset. The channel must not even
        // read preferences, since there is nothing it could do with them.
        channel(null).deliver(notification());

        verify(preferences, never()).get(any());
    }

    @Test
    void doesNotEmailAUserWhoHasNotOptedIn() {
        when(preferences.get(7L)).thenReturn(preference(false, "user@example.com"));

        channel(sender).deliver(notification());

        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void doesNotEmailWhenTheAddressIsMissing() {
        when(preferences.get(7L)).thenReturn(preference(true, null));

        channel(sender).deliver(notification());

        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void swallowsSmtpFailuresSoTheKafkaListenerNeverReplays() {
        // A throw here would fail the consumer, Kafka would redeliver, and every user who already
        // got their alert would get it a second time. The notification row is already durable.
        when(preferences.get(7L)).thenReturn(preference(true, "user@example.com"));
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> channel(sender).deliver(notification())).doesNotThrowAnyException();
    }

    private EmailChannel channel(JavaMailSender available) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return new EmailChannel(provider, preferences, properties, new SimpleMeterRegistry());
    }

    private static NotificationPreference preference(boolean enabled, String email) {
        return NotificationPreference.builder()
                .userId(7L)
                .email(email)
                .emailEnabled(enabled)
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
