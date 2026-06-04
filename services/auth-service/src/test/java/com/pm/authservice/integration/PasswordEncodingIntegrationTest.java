package com.pm.authservice.integration;

import com.pm.authservice.entity.Role;
import com.pm.authservice.entity.User;
import com.pm.authservice.enums.RoleName;
import com.pm.authservice.repository.RoleRepository;
import com.pm.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** DelegatingPasswordEncoder: new hashes are prefixed; legacy bare-bcrypt still works. */
class PasswordEncodingIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void newRegistrationsAreStoredWithBcryptPrefix() throws Exception {
        long id = uniqueId();
        String email = "enc" + id + "@finsight.test";
        register("user" + id, email, "password123");

        User stored = userRepository.findByEmail(email).orElseThrow();
        assertTrue(stored.getPassword().startsWith("{bcrypt}"),
                "new hashes must carry the {bcrypt} algorithm id");
    }

    @Test
    void legacyBareBcryptHashStillAuthenticates() throws Exception {
        long id = uniqueId();
        String email = "legacy" + id + "@finsight.test";
        Role role = roleRepository.findByName(RoleName.ROLE_USER).orElseThrow();

        // Simulates a pre-migration user: a raw bcrypt hash with no algorithm prefix.
        User legacy = User.builder()
                .username("legacy" + id)
                .email(email)
                .password(new BCryptPasswordEncoder().encode("password123"))
                .enabled(true)
                .role(role)
                .createdAt(LocalDateTime.now())
                .build();
        userRepository.save(legacy);

        // login() asserts HTTP 200 — proves the default decoder verifies prefix-less hashes.
        login(email, "password123");
    }
}
