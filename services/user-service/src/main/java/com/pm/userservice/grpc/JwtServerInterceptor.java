package com.pm.userservice.grpc;

import com.pm.userservice.security.jwt.JwtService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * gRPC equivalent of {@code JwtAuthenticationFilter}: authenticates every inbound call
 * from the {@code Authorization} metadata (a relayed {@code Bearer <token>}), validates
 * it with the SAME RS256 {@link JwtService} the REST layer uses, and exposes the caller's
 * {@code userId} to the service via {@link #USER_ID}. This keeps the invariant that
 * userId always comes from the validated JWT, never from the request body — even over gRPC.
 *
 * <p>Registered globally ({@link GlobalServerInterceptor}), so it guards every gRPC method.
 * A missing/invalid token closes the call with {@code UNAUTHENTICATED}.
 */
@Component
@GlobalServerInterceptor
public class JwtServerInterceptor implements ServerInterceptor {

    /** The authenticated caller's id, set on the gRPC {@link Context} for the service to read. */
    public static final Context.Key<Long> USER_ID = Context.key("userId");

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtServerInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String token = extractBearer(headers.get(AUTHORIZATION));
        if (token == null || !jwtService.validateToken(token)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid bearer token"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        Context context = Context.current().withValue(USER_ID, jwtService.extractUserId(token));
        return Contexts.interceptCall(context, call, headers, next);
    }

    private static String extractBearer(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length());
    }
}
