package com.pm.authservice.integration;

import com.pm.authservice.entity.User;
import com.pm.authservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A disabled account must not be able to authenticate, even with the right password. */
class DisabledAccountIntegrationTest extends AbstractMockMvcIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void disabledUserCannotLogin() throws Exception {
        long id = uniqueId();
        String email = "disabled" + id + "@finsight.test";
        register("user" + id, email, "password123");

        User user = userRepository.findByEmail(email).orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DISABLED"));
    }
}
