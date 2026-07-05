package com.pm.authservice.integration;

import com.pm.authservice.integration.support.JwtTestTokens;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests. Boots the full Spring context against a real MySQL 8
 * instance (Flyway-migrated) and a real Redis, both from Testcontainers.
 *
 * <p>Singleton-container pattern: each container is started once in a static
 * initializer and reused across every test class in the JVM (Ryuk reaps them on
 * shutdown). Connection details are pushed into the Spring {@code Environment} via
 * {@link DynamicPropertySource}, so the datasource, Flyway and the Redis-backed
 * refresh-token store all target the containers — nothing is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("auth_db");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // RS256 keypair for tests: auth-service signs logins with the private key and verifies
        // its own /me endpoint with the public key. JwtTestTokens mints with the same pair.
        registry.add("jwt.private-key", JwtTestTokens::privateKeyBase64);
        registry.add("jwt.public-key", JwtTestTokens::publicKeyBase64);
    }
}
