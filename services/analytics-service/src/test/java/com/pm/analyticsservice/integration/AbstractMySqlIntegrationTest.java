package com.pm.analyticsservice.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Base class for integration tests. Boots the full Spring context against a real MySQL 8
 * instance from Testcontainers, using the singleton-container pattern (started once in a
 * static initializer, reused across the JVM) that the other services use.
 *
 * <p>Booting the context is the point, not a side effect: Flyway applies every migration and
 * Hibernate then validates the entities against what it built, so a schema that has drifted
 * from its mapping fails here rather than on a production startup.
 *
 * <p>The app refuses to start without an RS256 public key, so an ephemeral one is generated
 * and injected. No test here mints tokens — these tests exercise persistence, not the API.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractMySqlIntegrationTest {

    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("analytics_db");

    private static final KeyPair KEYS = generateRsa();

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("jwt.public-key",
                () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    private static KeyPair generateRsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA unavailable", e);
        }
    }
}
