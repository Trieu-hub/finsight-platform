package com.pm.userservice.integration;

import com.pm.grpc.user.GetMyProfileRequest;
import com.pm.grpc.user.GetMyProfileResponse;
import com.pm.grpc.user.UserProfileServiceGrpc;
import com.pm.grpc.user.UserProfileServiceGrpc.UserProfileServiceBlockingStub;
import com.pm.userservice.dto.CreateProfileRequest;
import com.pm.userservice.grpc.JwtServerInterceptor;
import com.pm.userservice.grpc.UserProfileGrpcService;
import com.pm.userservice.integration.support.JwtTestTokens;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration test for the gRPC {@link UserProfileGrpcService} + {@link JwtServerInterceptor}.
 * The real beans (wired to a real MySQL container and the real RS256 {@code JwtService}) are
 * driven over an in-process gRPC server, so the whole path is exercised — JWT metadata
 * validation, userId derivation, DB read, proto mapping — without opening a socket or depending
 * on the autoconfigured server's port. Only the transport differs from production.
 */
class UserProfileGrpcIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final AtomicLong USER_SEQUENCE = new AtomicLong(500_000L);

    @Autowired
    private UserProfileGrpcService grpcService;
    @Autowired
    private JwtServerInterceptor jwtServerInterceptor;
    @Autowired
    private com.pm.userservice.service.UserProfileService userProfileService;

    private Server server;
    private ManagedChannel channel;
    private UserProfileServiceBlockingStub stub;

    @BeforeEach
    void startServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        // Same wiring as production: the global JWT interceptor guards the service.
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(grpcService, jwtServerInterceptor))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = UserProfileServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void stopServer() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private UserProfileServiceBlockingStub withToken(String token) {
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer " + token);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @Test
    void getMyProfile_returnsPersistedProfile_forValidToken() {
        long userId = USER_SEQUENCE.incrementAndGet();
        userProfileService.createProfile(userId, new CreateProfileRequest(
                "Nguyen Van A", "+84901234567", LocalDate.of(1990, 5, 1), null, "Engineer", "Hello"));
        String token = JwtTestTokens.valid(userId, "user" + userId + "@finsight.test", "ROLE_USER");

        GetMyProfileResponse response = withToken(token).getMyProfile(GetMyProfileRequest.getDefaultInstance());

        assertThat(response.getFound()).isTrue();
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getFullName()).isEqualTo("Nguyen Van A");
        assertThat(response.getPhone()).isEqualTo("+84901234567");
        assertThat(response.getDateOfBirth()).isEqualTo("1990-05-01");
        assertThat(response.getOccupation()).isEqualTo("Engineer");
        assertThat(response.getBio()).isEqualTo("Hello");
    }

    @Test
    void getMyProfile_returnsNotFound_whenNoProfileYet() {
        long userId = USER_SEQUENCE.incrementAndGet();
        String token = JwtTestTokens.valid(userId, "user" + userId + "@finsight.test", "ROLE_USER");

        GetMyProfileResponse response = withToken(token).getMyProfile(GetMyProfileRequest.getDefaultInstance());

        // Absent profile is a normal state, not an error (mirrors the REST 404 -> null contract).
        assertThat(response.getFound()).isFalse();
        assertThat(response.getFullName()).isEmpty();
    }

    @Test
    void getMyProfile_rejectsMissingToken_withUnauthenticated() {
        StatusRuntimeException error = catchThrowableOfType(
                StatusRuntimeException.class,
                () -> stub.getMyProfile(GetMyProfileRequest.getDefaultInstance()));

        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void getMyProfile_rejectsForgedToken_withUnauthenticated() {
        long userId = USER_SEQUENCE.incrementAndGet();
        String forged = JwtTestTokens.forgedSignature(userId, "user" + userId + "@finsight.test", "ROLE_USER");

        StatusRuntimeException error = catchThrowableOfType(
                StatusRuntimeException.class,
                () -> withToken(forged).getMyProfile(GetMyProfileRequest.getDefaultInstance()));

        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void getMyProfile_rejectsExpiredToken_withUnauthenticated() {
        long userId = USER_SEQUENCE.incrementAndGet();
        String expired = JwtTestTokens.expired(userId, "user" + userId + "@finsight.test", "ROLE_USER");

        assertThatThrownBy(() -> withToken(expired).getMyProfile(GetMyProfileRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }
}
