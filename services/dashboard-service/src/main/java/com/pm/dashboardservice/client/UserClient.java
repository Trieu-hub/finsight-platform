package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.UserProfileDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.grpc.user.GetMyProfileRequest;
import com.pm.grpc.user.GetMyProfileResponse;
import com.pm.grpc.user.UserProfileServiceGrpc.UserProfileServiceBlockingStub;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * Reads the caller's profile from user-service over <b>gRPC</b> — the platform's one
 * internal-sync gRPC call (transaction/budget stay REST). The caller's bearer token is
 * relayed as call metadata so user-service authorizes as the same user and derives the
 * userId from the validated JWT, exactly as the former REST call did. A per-call deadline
 * mirrors the REST read timeout so a slow/absent server fails fast (→ 502).
 */
@Component
public class UserClient {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final UserProfileServiceBlockingStub stub;
    private final long deadlineMs;
    private final UpstreamCalls upstreamCalls;

    public UserClient(UserProfileServiceBlockingStub stub, DashboardProperties properties,
                      UpstreamCalls upstreamCalls) {
        this.stub = stub;
        this.deadlineMs = properties.getTimeouts().getReadMs();
        this.upstreamCalls = upstreamCalls;
    }

    /**
     * @return the profile, or {@code null} if the user has not created one yet
     *         (user-service replies {@code found = false} — a normal, non-fatal state,
     *         the gRPC analogue of the REST 404 the callers already handle).
     */
    public UserProfileDto me(String authorization) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, authorization);
        // Guarded by the same circuit breaker + retry as the REST upstreams (UpstreamCalls maps a
        // transient StatusRuntimeException / a fail-fast open breaker to UpstreamException → 502).
        return upstreamCalls.call("user-service", () -> {
            GetMyProfileResponse response = stub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .getMyProfile(GetMyProfileRequest.getDefaultInstance());
            return response.getFound() ? toDto(response) : null;
        });
    }

    private static UserProfileDto toDto(GetMyProfileResponse r) {
        return new UserProfileDto(
                r.getUserId(),
                emptyToNull(r.getFullName()),
                emptyToNull(r.getPhone()),
                parseDate(r.getDateOfBirth()),
                emptyToNull(r.getAvatarUrl()),
                emptyToNull(r.getOccupation()),
                emptyToNull(r.getBio()));
    }

    /** proto3 sends absent strings as ""; restore the REST DTO's null semantics. */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static LocalDate parseDate(String value) {
        return value == null || value.isEmpty() ? null : LocalDate.parse(value);
    }
}
