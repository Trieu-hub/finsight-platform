package com.pm.userservice;

import com.pm.userservice.integration.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full application context boots against a real MySQL 8 container
 * (with Flyway running the migrations), the same way it runs in Docker Compose.
 */
class UserServiceApplicationTests extends AbstractMySqlIntegrationTest {

    @Test
    void contextLoads() {
    }
}
