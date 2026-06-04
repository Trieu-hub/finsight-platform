package com.pm.budgetservice.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests. Boots the full Spring context against a real
 * MySQL 8 instance provided by Testcontainers.
 *
 * <p>Uses the Testcontainers "singleton container" pattern: the container is started
 * once in a static initializer and reused across every test class in the JVM (Ryuk
 * reaps it on shutdown), which is far cheaper than starting one container per class.
 * Connection details are pushed into the Spring {@code Environment} via
 * {@link DynamicPropertySource} so both the datasource and Flyway target the container.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractMySqlIntegrationTest {

    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("budget_db");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }
}
