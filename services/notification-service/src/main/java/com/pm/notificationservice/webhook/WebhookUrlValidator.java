package com.pm.notificationservice.webhook;

import com.pm.notificationservice.exception.InvalidWebhookUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Decides whether this service is willing to POST to a URL the user supplied.
 *
 * <p>This is the security boundary of the webhook channel, not a niceness check. A webhook is the
 * one place where a user chooses an address the <i>server</i> then connects to, which is the
 * classic server-side request forgery shape — and this server sits on a Docker network where
 * {@code risk-service} answers unauthenticated, MySQL, Redis and Kafka are reachable by name, and
 * every service exposes {@code /actuator}. An unchecked URL turns "notify me" into "fetch that for
 * me from inside your network".
 *
 * <p>Two rules do the work:
 * <ul>
 *   <li><b>HTTPS only.</b> Alert text is financial wording and has no business crossing the
 *       internet in the clear. It also removes every plain-HTTP internal service from reach in one
 *       stroke, including {@code http://risk-service:8086} and the cloud metadata endpoints.</li>
 *   <li><b>No private destinations.</b> Every address the host resolves to must be publicly
 *       routable, so a public name that happens to point at {@code 127.0.0.1} is refused too.</li>
 * </ul>
 *
 * <p>Checked twice on purpose: when the user saves the URL, so they get a straight error, and again
 * before each delivery, because DNS can be repointed after the fact. That second check narrows but
 * does not close the gap between resolving a name and connecting to it — the JDK resolves again on
 * connect. Closing it fully means pinning the connection to a vetted IP, which breaks TLS host
 * verification; the deliberate trade here is validate-twice plus redirects disabled in
 * {@link WebhookChannel}, since a redirect is the easy way to smuggle a second, unvetted address in.
 */
@Component
public class WebhookUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookUrlValidator.class);

    /** Throws with a message meant for the user. Used on the save path. */
    public void validate(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidWebhookUrlException("Webhook URL is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidWebhookUrlException("Webhook URL must use https");
        }
        // Credentials in the URL would be stored in our database and replayed on every delivery.
        if (uri.getUserInfo() != null) {
            throw new InvalidWebhookUrlException("Webhook URL must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidWebhookUrlException("Webhook URL has no host");
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new InvalidWebhookUrlException("Webhook host could not be resolved");
        }
        for (InetAddress address : resolved) {
            if (isPrivate(address)) {
                throw new InvalidWebhookUrlException(
                        "Webhook URL must point at a public address");
            }
        }
    }

    /** The same check as a predicate, for the delivery path where there is no one to tell. */
    public boolean isAllowed(String url) {
        try {
            validate(url);
            return true;
        } catch (InvalidWebhookUrlException e) {
            log.warn("Refusing to call webhook {}: {}", url, e.getMessage());
            return false;
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress()          // 127/8, ::1
                || address.isAnyLocalAddress()   // 0.0.0.0, ::
                || address.isLinkLocalAddress()  // 169.254/16 (cloud metadata), fe80::/10
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] octets = address.getAddress();
        if (address instanceof Inet6Address) {
            // Unique local addresses, fc00::/7 — the IPv6 equivalent of 10/8, and not covered by
            // isSiteLocalAddress, which only answers for the deprecated fec0::/10 range.
            return (octets[0] & 0xFE) == 0xFC;
        }
        // Carrier-grade NAT, 100.64.0.0/10. Not "private" to the JDK, but it is not a destination
        // on the public internet either, and it is a known way past a naive private-range check.
        return (octets[0] & 0xFF) == 100 && (octets[1] & 0xFF) >= 64 && (octets[1] & 0xFF) <= 127;
    }
}
