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
    public void deliver(Notification notification) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            return;
        }
        NotificationPreference preference = preferences.get(notification.getUserId());
        if (!preference.isEmailEnabled() || preference.getEmail() == null) {
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.getFrom());
            message.setTo(preference.getEmail());
            message.setSubject(notification.getTitle());
            message.setText(notification.getMessage() + "\n\n" + properties.getFooter());
            sender.send(message);
            sent.increment();
        } catch (Exception e) {
            // Swallowed like every other channel: the notification is already durable and visible
            // in the app, and a broken SMTP server must not make the Kafka listener replay.
            failed.increment();
            log.warn("Alert email failed for user {}: {}", notification.getUserId(), e.toString());
        }
    }
}
