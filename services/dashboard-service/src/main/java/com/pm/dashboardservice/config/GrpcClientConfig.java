package com.pm.dashboardservice.config;

import com.pm.grpc.user.UserProfileServiceGrpc;
import com.pm.grpc.user.UserProfileServiceGrpc.UserProfileServiceBlockingStub;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/**
 * Builds the blocking stub for user-service's gRPC {@code UserProfileService}. The channel
 * named "user-service" is configured under {@code spring.grpc.client.channel} (target,
 * plaintext by default). gRPC connects lazily on first call, so this bean — and the whole
 * context — starts fine even when user-service is not yet up.
 */
@Configuration
public class GrpcClientConfig {

    @Bean
    UserProfileServiceBlockingStub userProfileServiceStub(GrpcChannelFactory channels) {
        return UserProfileServiceGrpc.newBlockingStub(channels.createChannel("user-service"));
    }
}
