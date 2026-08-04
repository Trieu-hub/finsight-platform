package com.pm.notificationservice.webhook;

import com.pm.notificationservice.exception.InvalidWebhookUrlException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSRF boundary. Every case here is an address a user could type in to make this service
 * connect somewhere it should not — the internal Docker network reaches an unauthenticated
 * risk-service, MySQL, Redis, Kafka and nine actuator endpoints.
 *
 * <p>Literal IPs throughout, so the assertions do not depend on DNS and the suite still passes
 * offline.
 */
class WebhookUrlValidatorTest {

    private final WebhookUrlValidator validator = new WebhookUrlValidator();

    @Test
    void acceptsAPublicHttpsUrl() {
        assertThatCode(() -> validator.validate("https://93.184.216.34/hooks/vernfy"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPlainHttp() {
        // This single rule is also what puts every http-only internal service out of reach —
        // http://risk-service:8086 among them.
        assertThatThrownBy(() -> validator.validate("http://93.184.216.34/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("https");
    }

    @Test
    void rejectsLoopback() {
        assertThatThrownBy(() -> validator.validate("https://127.0.0.1/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("public address");
    }

    @Test
    void rejectsPrivateRanges() {
        for (String host : new String[]{"10.0.0.5", "172.16.4.4", "192.168.1.1"}) {
            assertThatThrownBy(() -> validator.validate("https://" + host + "/hook"))
                    .as("private host %s", host)
                    .isInstanceOf(InvalidWebhookUrlException.class);
        }
    }

    @Test
    void rejectsTheCloudMetadataAddress() {
        // 169.254.169.254 is where AWS/GCP/Azure hand out instance credentials. It is link-local,
        // so it would sail past a check that only knew the RFC 1918 ranges.
        assertThatThrownBy(() -> validator.validate("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(InvalidWebhookUrlException.class);
    }

    @Test
    void rejectsCarrierGradeNat() {
        // 100.64.0.0/10 is not "site local" to the JDK, which is exactly why it is worth a test:
        // isSiteLocalAddress() alone would let it through.
        assertThatThrownBy(() -> validator.validate("https://100.64.0.1/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class);
    }

    @Test
    void rejectsIpv6UniqueLocalAndLoopback() {
        // fc00::/7 is IPv6's answer to 10/8 and is likewise invisible to isSiteLocalAddress().
        assertThatThrownBy(() -> validator.validate("https://[fd00::1]/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class);
        assertThatThrownBy(() -> validator.validate("https://[::1]/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class);
    }

    @Test
    void rejectsCredentialsInTheUrl() {
        // They would be stored in our database and replayed on every delivery.
        assertThatThrownBy(() -> validator.validate("https://user:pw@93.184.216.34/hook"))
                .isInstanceOf(InvalidWebhookUrlException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsGarbageAndHostlessUrls() {
        assertThatThrownBy(() -> validator.validate("not a url"))
                .isInstanceOf(InvalidWebhookUrlException.class);
        assertThatThrownBy(() -> validator.validate("https:///hook"))
                .isInstanceOf(InvalidWebhookUrlException.class);
    }

    @Test
    void isAllowedReportsRatherThanThrows() {
        // The delivery path has nobody to hand an exception to; it needs a yes/no and a log line.
        assertThat(validator.isAllowed("https://93.184.216.34/hook")).isTrue();
        assertThat(validator.isAllowed("https://127.0.0.1/hook")).isFalse();
    }
}
