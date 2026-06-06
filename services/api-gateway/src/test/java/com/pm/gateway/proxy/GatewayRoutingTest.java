package com.pm.gateway.proxy;

import com.pm.gateway.support.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Routing classification. No backend services run, so assertions are about routing
 * (not end-to-end forwarding):
 *
 * <ul>
 *   <li>An unknown prefix → {@code 404 ROUTE_NOT_FOUND} (gateway owns no route).</li>
 *   <li>A known, protected prefix <b>with a valid token</b> but no backend up →
 *       {@code 503 SERVICE_UNAVAILABLE} (route matched, auth passed, forward attempted),
 *       proving the route table works.</li>
 * </ul>
 *
 * Authentication behaviour itself is covered by {@code GatewayAuthTest}.
 */
@SpringBootTest(properties = {
        "gateway.routes[0].prefix=/api/v1/budgets",
        "gateway.routes[0].uri=http://localhost:59999",
        "gateway.timeouts.connect-ms=500",
        "gateway.timeouts.read-ms=500",
        "jwt.secret=" + GatewayRoutingTest.SECRET
})
@AutoConfigureMockMvc
class GatewayRoutingTest {

    static final String SECRET = "test-secret-test-secret-test-secret-test-secret-0123456789abcdef";

    @Autowired
    MockMvc mockMvc;

    @Test
    void unknownPrefixReturnsRouteNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/nope/123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void knownPrefixWithValidTokenAndDeadBackendReturnsServiceUnavailable() throws Exception {
        String token = JwtTestTokens.valid(SECRET, "finsight-auth", "finsight-api");
        mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }
}
