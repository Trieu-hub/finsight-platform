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
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

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

        channel(sender).deliver(7L, List.of(notification()));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        assertThat(message.getTo()).containsExactly("user@example.com");
        assertThat(message.getSubject()).isEqualTo("Large expense");
        // The alert text is the narrator's, unchanged, so the wording matches the in-app copy.
        assertThat(message.getText()).startsWith("You spent a lot.");
    }

    @Test
    void aDigestOfSeveralAlertsIsOneEmailThatListsThem() {
        when(preferences.get(7L)).thenReturn(preference(true, "user@example.com"));

        channel(sender).deliver(7L, List.of(notification(), notification("Budget exceeded", "Over by 200.")));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        // A batch announces itself; a single alert (above) keeps its own title as the subject, so
        // the immediate path reads exactly as it did before digests existed.
        assertThat(message.getSubject()).isEqualTo("2 Vernfy alerts");
        assertThat(message.getText())
                .contains("- Large expense: You spent a lot.")
                .contains("- Budget exceeded: Over by 200.");
    }

    @Test
    void summarisesTheTailOfAPathologicalBurst() {
        // 25 alerts must not become 25 unreadable lines. The bell holds the complete record; this
        // is the nudge towards it.
        when(preferences.get(7L)).thenReturn(preference(true, "user@example.com"));
        List<Notification> burst = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> notification("Alert " + i, "Body " + i))
                .toList();

        channel(sender).deliver(7L, burst);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        String text = captor.getValue().getText();
        assertThat(text).contains("- Alert 20: Body 20").contains("and 5 more in the app");
        assertThat(text).doesNotContain("Alert 21");
    }

    @Test
    void staysSilentWhenNoMailServerIsConfigured() {
        // No JavaMailSender bean is the default: spring.mail.host unset. The channel must not even
        // read preferences, since there is nothing it could do with them.
        channel(null).deliver(7L, List.of(notification()));

        verify(preferences, never()).get(any());
    }

    @Test
    void doesNotEmailAUserWhoHasNotOptedIn() {
        when(preferences.get(7L)).thenReturn(preference(false, "user@example.com"));

        channel(sender).deliver(7L, List.of(notification()));

        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void doesNotEmailWhenTheAddressIsMissing() {
        when(preferences.get(7L)).thenReturn(preference(true, null));

        channel(sender).deliver(7L, List.of(notification()));

        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void swallowsSmtpFailuresSoTheKafkaListenerNeverReplays() {
        // A throw here would fail the consumer, Kafka would redeliver, and every user who already
        // got their alert would get it a second time. The notification row is already durable.
        when(preferences.get(7L)).thenReturn(preference(true, "user@example.com"));
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> channel(sender).deliver(7L, List.of(notification()))).doesNotThrowAnyException();
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
        return notification("Large expense", "You spent a lot.");
    }

    private static Notification notification(String title, String message) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(7L)
                .type("RISK_ALERT")
                .severity("HIGH")
                .title(title)
                .message(message)
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
