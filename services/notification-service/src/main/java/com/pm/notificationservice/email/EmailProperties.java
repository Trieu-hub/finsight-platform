package com.pm.notificationservice.email;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The parts of an alert email that are not the alert itself. The SMTP connection is configured
 * under Spring's own {@code spring.mail.*}; whether that is present is what turns the channel on.
 */
@ConfigurationProperties(prefix = "finsight.email")
@Getter
@Setter
public class EmailProperties {

    /**
     * Envelope sender. Must be on a domain whose SPF/DKIM authorise the SMTP server, or the mail
     * lands in spam — see docs/deploy.md.
     */
    private String from = "Vernfy <noreply@vernfy.com>";

    /** Appended to every alert so a recipient knows where it came from and how to stop it. */
    private String footer = "You are receiving this because email alerts are on in Vernfy. "
            + "Turn them off in the notification bell.";
}
