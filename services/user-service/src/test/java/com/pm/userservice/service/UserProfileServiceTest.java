package com.pm.userservice.service;

import com.pm.userservice.dto.CreateProfileRequest;
import com.pm.userservice.dto.UpdateProfileRequest;
import com.pm.userservice.dto.UserProfileResponse;
import com.pm.userservice.entity.UserProfile;
import com.pm.userservice.exception.ProfileAlreadyExistsException;
import com.pm.userservice.exception.ProfileNotFoundException;
import com.pm.userservice.repository.UserProfileRepository;
import com.pm.userservice.service.impl.UserProfileServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private UserProfileServiceImpl userProfileService;

    // ─── createProfile ───────────────────────────────────────────────────────

    @Test
    void createProfile_success_returnsResponse() {
        when(userProfileRepository.existsById(1L)).thenReturn(false);

        UserProfile saved = UserProfile.builder()
                .userId(1L)
                .fullName("Nguyen Van A")
                .phone("+84901234567")
                .dateOfBirth(LocalDate.of(1995, 6, 15))
                .occupation("Engineer")
                .bio("Hello")
                .build();
        when(userProfileRepository.save(any())).thenReturn(saved);

        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("Nguyen Van A");
        request.setPhone("+84901234567");
        request.setDateOfBirth(LocalDate.of(1995, 6, 15));
        request.setOccupation("Engineer");
        request.setBio("Hello");

        UserProfileResponse response = userProfileService.createProfile(1L, request);

        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(response.getPhone()).isEqualTo("+84901234567");
        assertThat(response.getOccupation()).isEqualTo("Engineer");
    }

    @Test
    void createProfile_duplicateProfile_throwsConflict() {
        when(userProfileRepository.existsById(1L)).thenReturn(true);

        CreateProfileRequest request = new CreateProfileRequest();
        request.setFullName("Nguyen Van A");

        assertThatThrownBy(() -> userProfileService.createProfile(1L, request))
                .isInstanceOf(ProfileAlreadyExistsException.class)
                .hasMessageContaining("already exists");
    }

    // ─── getMyProfile ────────────────────────────────────────────────────────

    @Test
    void getMyProfile_success_returnsProfile() {
        UserProfile profile = UserProfile.builder()
                .userId(1L)
                .fullName("Nguyen Van A")
                .bio("Hello")
                .build();
        when(userProfileRepository.findById(1L)).thenReturn(Optional.of(profile));

        UserProfileResponse response = userProfileService.getMyProfile(1L);

        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(response.getBio()).isEqualTo("Hello");
    }

    @Test
    void getMyProfile_notFound_throwsException() {
        when(userProfileRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.getMyProfile(99L))
                .isInstanceOf(ProfileNotFoundException.class)
                .hasMessageContaining("not found");
    }

    // ─── updateProfile ───────────────────────────────────────────────────────

    @Test
    void updateProfile_partialFields_onlyUpdatesNonNull() {
        UserProfile profile = UserProfile.builder()
                .userId(1L)
                .fullName("Original Name")
                .bio("Original bio")
                .phone("+84900000000")
                .build();
        when(userProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(userProfileRepository.save(any())).thenReturn(profile);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setBio("Updated bio"); // only bio changes

        UserProfileResponse response = userProfileService.updateProfile(1L, request);

        assertThat(response.getBio()).isEqualTo("Updated bio");
        assertThat(response.getFullName()).isEqualTo("Original Name");  // unchanged
        assertThat(response.getPhone()).isEqualTo("+84900000000");       // unchanged
    }

    @Test
    void updateProfile_allFields_updatesAll() {
        UserProfile profile = UserProfile.builder()
                .userId(1L)
                .fullName("Old Name")
                .bio("Old bio")
                .phone("+84900000000")
                .occupation("Old job")
                .build();
        when(userProfileRepository.findById(1L)).thenReturn(Optional.of(profile));
        when(userProfileRepository.save(any())).thenReturn(profile);

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setFullName("New Name");
        request.setBio("New bio");
        request.setPhone("+84911111111");
        request.setOccupation("New job");
        request.setDateOfBirth(LocalDate.of(1990, 1, 1));

        UserProfileResponse response = userProfileService.updateProfile(1L, request);

        assertThat(response.getFullName()).isEqualTo("New Name");
        assertThat(response.getBio()).isEqualTo("New bio");
        assertThat(response.getPhone()).isEqualTo("+84911111111");
        assertThat(response.getOccupation()).isEqualTo("New job");
    }

    @Test
    void updateProfile_notFound_throwsException() {
        when(userProfileRepository.findById(99L)).thenReturn(Optional.empty());

        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setBio("New bio");

        assertThatThrownBy(() -> userProfileService.updateProfile(99L, request))
                .isInstanceOf(ProfileNotFoundException.class)
                .hasMessageContaining("not found");
    }
}
