package com.pm.dashboardservice;

import com.pm.dashboardservice.support.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the full context (security + RestClient beans + config properties). No upstream
 * calls happen at startup, so no backends are required — this just proves the wiring.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "dashboard.services.transaction-uri=http://localhost:8083",
        "dashboard.services.budget-uri=http://localhost:8084"
})
class DashboardApplicationTests {

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("jwt.public-key", JwtTestTokens::publicKeyBase64);
    }

    @Test
    void contextLoads() {
    }
}
