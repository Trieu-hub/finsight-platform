package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.UserProfileDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.exception.UpstreamException;
import com.pm.dashboardservice.support.TestUpstreamCalls;
import com.pm.grpc.user.GetMyProfileRequest;
import com.pm.grpc.user.GetMyProfileResponse;
import com.pm.grpc.user.UserProfileServiceGrpc;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the gRPC {@link UserClient} against a real in-process gRPC server (no sockets),
 * covering the three mappings the BFF relies on: a found profile → DTO (with the bearer token
 * relayed as metadata), {@code found = false} → null, and a server-side error → UpstreamException.
 */
class UserClientTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    /** Starts an in-process server with the given service, returns a client wired to it. */
    private UserClient clientFor(BindableService service, AtomicReference<String> capturedAuth) throws IOException {
        ServerInterceptor authCapture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                capturedAuth.set(headers.get(AUTHORIZATION));
                return next.startCall(call, headers);
            }
        };
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service, authCapture))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return new UserClient(UserProfileServiceGrpc.newBlockingStub(channel), new DashboardProperties(),
                TestUpstreamCalls.create());
    }

    @Test
    void me_returnsProfile_andRelaysBearerTokenAsMetadata() throws IOException {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        UserClient client = clientFor(new UserProfileServiceGrpc.UserProfileServiceImplBase() {
            @Override
            public void getMyProfile(GetMyProfileRequest request, StreamObserver<GetMyProfileResponse> obs) {
                obs.onNext(GetMyProfileResponse.newBuilder()
                        .setFound(true)
                        .setUserId(1L)
                        .setFullName("Nguyen Van A")
                        .setPhone("+84901234567")
                        .setDateOfBirth("1990-05-01")
                        .setOccupation("Engineer")
                        .build());
                obs.onCompleted();
            }
        }, capturedAuth);

        UserProfileDto profile = client.me("Bearer t");

        assertThat(profile).isNotNull();
        assertThat(profile.userId()).isEqualTo(1L);
        assertThat(profile.fullName()).isEqualTo("Nguyen Van A");
        assertThat(profile.dateOfBirth()).isEqualTo(LocalDate.of(1990, 5, 1));
        assertThat(profile.occupation()).isEqualTo("Engineer");
        // proto3 sends absent strings as ""; the client restores null.
        assertThat(profile.avatarUrl()).isNull();
        // The caller's bearer token reached the server as gRPC metadata.
        assertThat(capturedAuth.get()).isEqualTo("Bearer t");
    }

    @Test
    void me_returnsNull_whenProfileNotFound() throws IOException {
        UserClient client = clientFor(new UserProfileServiceGrpc.UserProfileServiceImplBase() {
            @Override
            public void getMyProfile(GetMyProfileRequest request, StreamObserver<GetMyProfileResponse> obs) {
                obs.onNext(GetMyProfileResponse.newBuilder().setFound(false).build());
                obs.onCompleted();
            }
        }, new AtomicReference<>());

        assertThat(client.me("Bearer t")).isNull();
    }

    @Test
    void me_wrapsServerErrorAsUpstreamException() throws IOException {
        UserClient client = clientFor(new UserProfileServiceGrpc.UserProfileServiceImplBase() {
            @Override
            public void getMyProfile(GetMyProfileRequest request, StreamObserver<GetMyProfileResponse> obs) {
                obs.onError(Status.INTERNAL.withDescription("boom").asRuntimeException());
            }
        }, new AtomicReference<>());

        assertThatThrownBy(() -> client.me("Bearer t"))
                .isInstanceOf(UpstreamException.class);
    }
}
