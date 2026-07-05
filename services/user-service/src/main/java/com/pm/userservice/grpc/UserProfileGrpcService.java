package com.pm.userservice.grpc;

import com.pm.grpc.user.GetMyProfileRequest;
import com.pm.grpc.user.GetMyProfileResponse;
import com.pm.grpc.user.UserProfileServiceGrpc;
import com.pm.userservice.dto.UserProfileResponse;
import com.pm.userservice.exception.ProfileNotFoundException;
import com.pm.userservice.service.UserProfileService;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.time.LocalDate;

/**
 * gRPC facade over {@link UserProfileService}, mirroring the REST {@code GET /api/v1/users/me}.
 * The caller's id comes from {@link JwtServerInterceptor#USER_ID} (set from the validated JWT),
 * never from the request. A missing profile is a normal state: it maps to {@code found = false}
 * (the gRPC analogue of the REST 404 the dashboard already treats as "no profile yet"), not an error.
 */
@GrpcService
public class UserProfileGrpcService extends UserProfileServiceGrpc.UserProfileServiceImplBase {

    private final UserProfileService userProfileService;

    public UserProfileGrpcService(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @Override
    public void getMyProfile(GetMyProfileRequest request, StreamObserver<GetMyProfileResponse> responseObserver) {
        Long userId = JwtServerInterceptor.USER_ID.get();
        GetMyProfileResponse.Builder response = GetMyProfileResponse.newBuilder();
        try {
            UserProfileResponse profile = userProfileService.getMyProfile(userId);
            LocalDate dob = profile.getDateOfBirth();
            response.setFound(true)
                    .setUserId(profile.getUserId())
                    .setFullName(nullToEmpty(profile.getFullName()))
                    .setPhone(nullToEmpty(profile.getPhone()))
                    .setDateOfBirth(dob == null ? "" : dob.toString())
                    .setAvatarUrl(nullToEmpty(profile.getAvatarUrl()))
                    .setOccupation(nullToEmpty(profile.getOccupation()))
                    .setBio(nullToEmpty(profile.getBio()));
        } catch (ProfileNotFoundException e) {
            response.setFound(false);
        }
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    /** proto3 string fields cannot hold null; absent optional profile fields become "". */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
