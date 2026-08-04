package com.pm.notificationservice.email;

import com.pm.notificationservice.delivery.DeliveryChannel;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.service.NotificationPreferenceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Emails an alert to users who asked for it.
 *
 * <p>There is no {@code enabled} flag: Spring only creates a {@link JavaMailSender} when
 * {@code spring.mail.host} is set, so the presence of SMTP configuration <i>is</i> the switch —
 * the same shape as the VAPID keys gating web push. A checkout with no mail server configured
 * simply never sends, and nothing else changes.
 *
 * <p>The body is the text the narrator already produced for the in-app notification, so the alert
 * reads identically wherever it arrives and there is no second place to keep wording in sync.
 */
@Component
public class EmailChannel implements DeliveryChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    /** How many alerts a digest email spells out before it summarises the remainder. */
    private static final int MAX_LISTED = 20;

    private final ObjectProvider<JavaMailSender> mailSender;
    private final NotificationPreferenceService preferences;
    private final EmailProperties properties;
    private final Counter sent;
    private final Counter failed;

    public EmailChannel(ObjectProvider<JavaMailSender> mailSender,
                        NotificationPreferenceService preferences,
                        EmailProperties properties,
                        MeterRegistry meterRegistry) {
        this.mailSender = mailSender;
        this.preferences = preferences;
        this.properties = properties;
        this.sent = Counter.builder("finsight.notifications.email.sent")
                .description("Alert emails handed to the SMTP server")
                .register(meterRegistry);
        this.failed = Counter.builder("finsight.notifications.email.failed")
                .description("Alert emails that could not be sent")
                .register(meterRegistry);
    }

    @Override
    public boolean respectsDigest() {
        return true;
    }

    @Override
    public void deliver(Long userId, List<Notification> batch) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return;
        }
        NotificationPreference preference = preferences.get(userId);
        if (!preference.isEmailEnabled() || preference.getEmail() == null) {
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getFrom());
            message.setTo(preference.getEmail());
            message.setSubject(subject(batch));
            message.setText(body(batch) + "\n\n" + properties.getFooter());
            sender.send(message);
            sent.increment();
        } catch (Exception e) {
            // Swallowed like every other channel: the notifications are already durable and visible
            // in the app, and a broken SMTP server must not make the Kafka listener replay.
            failed.increment();
            log.warn("Alert email failed for user {}: {}", userId, e.toString());
        }
    }

    /**
     * A single alert keeps the subject it always had — its own title — so the immediate path reads
     * exactly as it did before digests existed. Only a real batch announces itself as one.
     */
    private static String subject(List<Notification> batch) {
        return batch.size() == 1
                ? batch.get(0).getTitle()
                : batch.size() + " Vernfy alerts";
    }

    private static String body(List<Notification> batch) {
        if (batch.size() == 1) {
            return batch.get(0).getMessage();
        }
        StringBuilder text = new StringBuilder();
        for (Notification notification : batch.subList(0, Math.min(batch.size(), MAX_LISTED))) {
            text.append("- ").append(notification.getTitle())
                    .append(": ").append(notification.getMessage()).append('\n');
        }
        // A pathological burst must not turn into an unreadable wall of text. The rest are still in
        // the app; the bell is the complete record, this is the nudge towards it.
        if (batch.size() > MAX_LISTED) {
            text.append("- and ").append(batch.size() - MAX_LISTED).append(" more in the app\n");
        }
        return text.toString();
    }
}
