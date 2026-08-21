package com.pm.authservice.integration;

import com.pm.authservice.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whether a sign-in leaves a trace anyone can read afterwards.
 *
 * <p>It did not, until now: the only evidence a person had ever used the platform was a Redis
 * refresh token, which vanishes when it expires. Asked "did anyone log in on the 19th?", the
 * honest answer was that nothing on the box could say. These two mechanisms — a counter for the
 * rate, a column for the identity — are what make that question answerable, so they are worth an
 * integration test rather than a mocked one: the column has to survive a real commit, and the
 * counter has to be registered in the real context.
 */
class LoginObservabilityIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private UserRepository userRepository;

    private double loginCount(String outcome) {
        return meterRegistry.get("finsight.auth.login").tag("outcome", outcome).counter().count();
    }

    private void login(String email, String password, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("counts a successful sign-in and stamps the account with it")
    void recordsASuccessfulLogin() throws Exception {
        long id = uniqueId();
        String email = "obs" + id + "@finsight.test";
        register("user" + id, email, "trailhead lantern 88");
        assertThat(userRepository.findByEmail(email).orElseThrow().getLastLoginAt()).isNull();

        double before = loginCount("success");
        login(email, "trailhead lantern 88", 200);

        assertThat(loginCount("success")).isEqualTo(before + 1);
        // The column is what answers "how many distinct people used this last week" — a counter
        // can only ever answer "how many logins", which is a different question.
        assertThat(userRepository.findByEmail(email).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("counts a failed sign-in without stamping the account")
    void recordsAFailedLogin() throws Exception {
        long id = uniqueId();
        String email = "obsfail" + id + "@finsight.test";
        register("user" + id, email, "trailhead lantern 88");

        double before = loginCount("bad_credentials");
        login(email, "wrong-password", 401);

        assertThat(loginCount("bad_credentials")).isEqualTo(before + 1);
        // A failed attempt is not a sign-in. Stamping here would make a brute-force sweep look
        // like a wave of active users.
        assertThat(userRepository.findByEmail(email).orElseThrow().getLastLoginAt()).isNull();
    }

    @Test
    @DisplayName("counts an unknown email the same as a wrong password")
    void doesNotLeakAccountExistenceThroughMetrics() throws Exception {
        double before = loginCount("bad_credentials");

        login("nobody" + uniqueId() + "@finsight.test", "whatever", 401);

        // The API deliberately answers the same for both; a metric that split them would leak
        // through Prometheus exactly what the response refuses to reveal.
        assertThat(loginCount("bad_credentials")).isEqualTo(before + 1);
        assertThatCode(() -> loginCount("unknown_user")).isInstanceOf(MeterNotFoundException.class);
    }

    @Test
    @DisplayName("publishes every outcome from startup, so a quiet day is not a missing panel")
    void registersAllOutcomesUpFront() {
        // A counter that has never fired is absent from /actuator/prometheus, and an absent
        // series renders as "no data" — indistinguishable from a dead service.
        for (String outcome : new String[]{"success", "bad_credentials", "locked", "disabled"}) {
            assertThatCode(() -> loginCount(outcome)).doesNotThrowAnyException();
        }
    }
}
