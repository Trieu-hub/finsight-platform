package com.pm.notificationservice.push;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * VAPID configuration for web push. Both keys empty (the default) means the channel is off, the
 * same way {@code finsight.narrator.ai.enabled=false} leaves the LLM narrator out of the picture.
 *
 * <p>The keypair is generated once by the operator (see {@code docs/deploy.md}) and is <b>not</b>
 * the JWT signing key: it is an EC P-256 pair whose only job is to prove to the browser's push
 * service that the push came from this application.
 */
@ConfigurationProperties(prefix = "finsight.push")
@Getter
@Setter
public class PushProperties {

    /** base64url of the 65-byte uncompressed EC point. Handed to the browser verbatim. */
    private String publicKey = "";

    /** base64url of the 32-byte private scalar. Never leaves this service. */
    private String privateKey = "";

    /**
     * VAPID {@code sub} claim: a mailto: or https: URL a push service operator could use to
     * contact whoever is sending. Must be a real contact point, not a placeholder.
     */
    private String subject = "https://vernfy.com";

    /** How long the push service should hold the message for a browser that is offline. */
    private int ttlSeconds = 86400;

    private long timeoutMs = 5000;

    /** The channel stays inert until both halves of the keypair are present. */
    public boolean isConfigured() {
        return !publicKey.isBlank() && !privateKey.isBlank();
    }
}
