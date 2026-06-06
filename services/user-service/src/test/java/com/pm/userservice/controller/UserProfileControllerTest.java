package com.pm.userservice.controller;

import tools.jackson.databind.ObjectMapper;
import com.pm.userservice.dto.CreateProfileRequest;
import com.pm.userservice.dto.UpdateProfileRequest;
import com.pm.userservice.dto.UserProfileResponse;
import com.pm.userservice.exception.ProfileNotFoundException;
import com.pm.userservice.integration.AbstractMySqlIntegrationTest;
import com.pm.userservice.security.JwtUserPrincipal;
import com.pm.userservice.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test: boots the full context (on the shared MySQL container, via the base
 * class) but mocks the service so it can assert controller behaviour — validation,
 * status codes, and the security filter — independently of persistence.
 */
class UserProfileControllerTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserProfileService userProfileService;

    private MockMvc mockMvc;

    private static final Long TEST_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private Authentication auth() {
        JwtUserPrincipal principal = new JwtUserPrincipal(TEST_USER_ID, "test@example.com", "ROLE_USER");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    // ─── POST /api/v1/users/me ───────────────────────────────────────────────

    @Test
    void createProfile_success_returns201() throws Exception {
        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("Nguyen Van A");

        UserProfileResponse response = UserProfileResponse.builder()
                .userId(TEST_USER_ID)
                .fullName("Nguyen Van A")
                .build();
        when(userProfileService.createProfile(eq(TEST_USER_ID), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
                .andExpect(jsonPath("$.fullName").value("Nguyen Van A"));
    }

    @Test
    void createProfile_allFields_mapsCorrectly() throws Exception {
        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("Nguyen Van A");
        request.setPhone("+84901234567");
        request.setDateOfBirth(LocalDate.of(1995, 6, 15));
        request.setOccupation("Engineer");
        request.setBio("Hello world");

        UserProfileResponse response = UserProfileResponse.builder()
                .userId(TEST_USER_ID)
                .fullName("Nguyen Van A")
                .phone("+84901234567")
                .occupation("Engineer")
                .bio("Hello world")
                .build();
        when(userProfileService.createProfile(eq(TEST_USER_ID), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("+84901234567"))
                .andExpect(jsonPath("$.occupation").value("Engineer"))
                .andExpect(jsonPath("$.bio").value("Hello world"));
    }

    // ─── GET /api/v1/users/me ────────────────────────────────────────────────

    @Test
    void getMyProfile_success_returns200() throws Exception {
        UserProfileResponse response = UserProfileResponse.builder()
                .userId(TEST_USER_ID)
                .fullName("Nguyen Van A")
                .bio("Hello")
                .build();
        when(userProfileService.getMyProfile(TEST_USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
                .andExpect(jsonPath("$.fullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.bio").value("Hello"));
    }

    @Test
    void getMyProfile_notFound_returns404() throws Exception {
        when(userProfileService.getMyProfile(TEST_USER_ID))
                .thenThrow(new ProfileNotFoundException("Profile not found"));

        mockMvc.perform(get("/api/v1/users/me")
                        .with(authentication(auth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Profile not found"));
    }

    // ─── PUT /api/v1/users/me ────────────────────────────────────────────────

    @Test
    void updateProfile_success_returns200() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setBio("Updated bio");

        UserProfileResponse response = UserProfileResponse.builder()
                .userId(TEST_USER_ID)
                .fullName("Nguyen Van A")
                .bio("Updated bio")
                .build();
        when(userProfileService.updateProfile(eq(TEST_USER_ID), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Updated bio"))
                .andExpect(jsonPath("$.fullName").value("Nguyen Van A"));
    }

    @Test
    void updateProfile_notFound_returns404() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setBio("New bio");

        when(userProfileService.updateProfile(eq(TEST_USER_ID), any()))
                .thenThrow(new ProfileNotFoundException("Profile not found"));

        mockMvc.perform(put("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ─── Validation ──────────────────────────────────────────────────────────

    @Test
    void createProfile_missingFullName_returns400() throws Exception {
        CreateProfileRequest request = new CreateProfileRequest();
        // fullName is null — @NotBlank rejects it

        mockMvc.perform(post("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Full name is required"));
    }

    @Test
    void createProfile_blankFullName_returns400() throws Exception {
        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("   ");

        mockMvc.perform(post("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createProfile_invalidPhone_returns400() throws Exception {
        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("Test User");
        request.setPhone("not-a-phone");

        mockMvc.perform(post("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid phone number format"));
    }

    @Test
    void createProfile_futureDateOfBirth_returns400() throws Exception {
        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("Test User");
        request.setDateOfBirth(LocalDate.now().plusYears(1));

        mockMvc.perform(post("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Date of birth must be in the past"));
    }

    @Test
    void updateProfile_invalidPhone_returns400() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setPhone("abc");

        mockMvc.perform(put("/api/v1/users/me")
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ─── Security ────────────────────────────────────────────────────────────

    @Test
    void request_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postRequest_withoutToken_returns401() throws Exception {
        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("Test User");

        mockMvc.perform(post("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_withInvalidToken_returns401() throws Exception {
        // Real JwtService attempts to parse "invalid-token" → JJWT throws → validateToken returns false → 401
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void request_withValidAuth_passesThrough() throws Exception {
        UserProfileResponse response = UserProfileResponse.builder()
                .userId(TEST_USER_ID)
                .fullName("Test User")
                .build();
        when(userProfileService.getMyProfile(TEST_USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(authentication(auth())))
                .andExpect(status().isOk());
    }
}
