package com.pm.gateway.proxy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 routing validation. No backend services run, so the assertion is about
 * routing classification, not end-to-end forwarding:
 *
 * <ul>
 *   <li>An unknown prefix → {@code 404 ROUTE_NOT_FOUND} (the gateway recognises it
 *       owns no route).</li>
 *   <li>A known prefix with no backend up → {@code 503 SERVICE_UNAVAILABLE} (the
 *       route matched and a forward was attempted), proving the route table works.</li>
 * </ul>
 *
 * The 503 (not 404) on a known prefix is exactly what distinguishes "routed but
 * downstream down" from "no route" — the core property Phase 1 must demonstrate.
 */
@SpringBootTest(properties = {
        // Point a known prefix at a definitely-dead port so the forward fails fast.
        "gateway.routes[0].prefix=/api/v1/budgets",
        "gateway.routes[0].uri=http://localhost:59999",
        "gateway.timeouts.connect-ms=500",
        "gateway.timeouts.read-ms=500"
})
@AutoConfigureMockMvc
class GatewayRoutingTest {

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
    void knownPrefixWithDeadBackendReturnsServiceUnavailable() throws Exception {
        mockMvc.perform(get("/api/v1/budgets"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }
}
