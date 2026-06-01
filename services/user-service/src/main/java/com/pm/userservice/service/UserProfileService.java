package com.pm.userservice.service;

import com.pm.userservice.dto.CreateProfileRequest;
import com.pm.userservice.dto.UpdateProfileRequest;
import com.pm.userservice.dto.UserProfileResponse;

public interface UserProfileService {

    UserProfileResponse createProfile(Long userId, CreateProfileRequest request);

    UserProfileResponse getMyProfile(Long userId);

    UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request);
}
