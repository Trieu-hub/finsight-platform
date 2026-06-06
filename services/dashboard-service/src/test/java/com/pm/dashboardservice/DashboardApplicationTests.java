package com.pm.dashboardservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the full context (security + RestClient beans + config properties). No upstream
 * calls happen at startup, so no backends are required — this just proves the wiring.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "jwt.secret=test-secret-test-secret-test-secret-test-secret-0123456789abcdef",
        "dashboard.services.user-uri=http://localhost:8082",
        "dashboard.services.transaction-uri=http://localhost:8083",
        "dashboard.services.budget-uri=http://localhost:8084"
})
class DashboardApplicationTests {

    @Test
    void contextLoads() {
    }
}
