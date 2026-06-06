package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.UserProfileDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.exception.UpstreamException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Reads the caller's profile from user-service (returned raw, not enveloped). */
@Component
public class UserClient {

    private final RestClient client;

    public UserClient(RestClient.Builder builder, DashboardProperties properties) {
        this.client = builder.baseUrl(properties.getServices().getUserUri()).build();
    }

    /**
     * @return the profile, or {@code null} if the user has not created one yet
     *         (user-service returns 404 in that case — a normal, non-fatal state).
     */
    public UserProfileDto me(String authorization) {
        try {
            return client.get()
                    .uri("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    // exchange() does not throw on error status, so we classify it ourselves:
                    // 404 → no profile yet (null); any other error → upstream failure (502).
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 404) {
                            return null;
                        }
                        if (status.isError()) {
                            throw new UpstreamException("user-service",
                                    new RestClientException("user-service returned " + status));
                        }
                        return response.bodyTo(UserProfileDto.class);
                    });
        } catch (RestClientException e) {
            throw new UpstreamException("user-service", e);
        }
    }
}
