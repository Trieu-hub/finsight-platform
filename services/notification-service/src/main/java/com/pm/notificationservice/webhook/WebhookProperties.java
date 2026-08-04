package com.pm.notificationservice.webhook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the outbound webhook channel. Unlike push and email there is nothing here that turns
 * the channel on: a webhook is enabled per user, by that user supplying a URL, so there is no
 * server-side switch to forget.
 */
@ConfigurationProperties(prefix = "finsight.webhook")
@Getter
@Setter
public class WebhookProperties {

    /**
     * Connect and read timeout. Short on purpose: this runs on the Kafka consumer thread (or the
     * digest scheduler's), and a receiver that hangs must not hold either of them.
     */
    private long timeoutMs = 5000;
}
