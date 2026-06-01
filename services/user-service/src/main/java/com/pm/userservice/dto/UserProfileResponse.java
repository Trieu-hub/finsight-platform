package com.pm.userservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    private Long userId;
    private String fullName;
    private String phone;
    private LocalDate dateOfBirth;
    private String avatarUrl;
    private String occupation;
    private String bio;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
