package com.pm.notificationservice.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();

    @Test
    void signsTheTimestampAndBodyTogether() throws Exception {
        // Recomputed independently rather than pinned to a golden string: this asserts the scheme
        // a receiver will implement (HMAC over "<t>.<body>"), not that the output has not changed.
        String body = "{\"count\":1}";
        long timestamp = 1754300000L;

        String header = signer.header("whsec_test", timestamp, body);

        assertThat(header).isEqualTo("t=" + timestamp + ",v1=" + hmac("whsec_test", timestamp + "." + body));
    }

    @Test
    void coversTheTimestampSoItCannotBeRewritten() {
        // If the MAC were over the body alone, an attacker could replay a captured request forever
        // by moving t forward. Same body, different t must give a different signature.
        String body = "{\"count\":1}";

        assertThat(signer.header("whsec_test", 1000L, body))
                .isNotEqualTo(signer.header("whsec_test", 2000L, body));
    }

    @Test
    void adifferentSecretProducesADifferentSignature() {
        String body = "{\"count\":1}";

        assertThat(signer.header("whsec_a", 1000L, body))
                .isNotEqualTo(signer.header("whsec_b", 1000L, body));
    }

    @Test
    void mintsPrefixedSecretsThatDoNotRepeat() {
        String first = WebhookSigner.newSecret();
        String second = WebhookSigner.newSecret();

        assertThat(first).startsWith("whsec_").isNotEqualTo(second);
        // 32 random bytes as unpadded base64url, plus the prefix — and it has to fit the
        // VARCHAR(64) the migration reserves for it.
        assertThat(first).hasSize(49);
    }

    private static String hmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
}
