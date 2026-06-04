package com.pm.authservice;

import com.pm.authservice.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: the full context boots against the Testcontainers MySQL + Redis.
 * Extends {@link AbstractIntegrationTest} so it is hermetic (no reliance on a
 * locally-running database or Redis).
 */
class AuthServiceApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }

}
