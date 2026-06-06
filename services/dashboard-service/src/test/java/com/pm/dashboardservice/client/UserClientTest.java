package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.UserProfileDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.exception.UpstreamException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserClientTest {

    private UserClient newClient(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        DashboardProperties props = new DashboardProperties();
        props.getServices().setUserUri("http://user-service:8082");
        return new UserClient(builder, props);
    }

    @Test
    void me_returnsRawProfile_andRelaysJwt() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        UserClient client = newClient(holder);
        String json = """
                {"userId":1,"fullName":"Nguyen Van A","phone":"+84901234567","occupation":"Engineer"}
                """;
        holder[0].expect(requestTo("http://user-service:8082/api/v1/users/me"))
                .andExpect(header("Authorization", "Bearer t"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        UserProfileDto profile = client.me("Bearer t");

        assertThat(profile).isNotNull();
        assertThat(profile.fullName()).isEqualTo("Nguyen Van A");
        holder[0].verify();
    }

    @Test
    void me_returnsNull_whenProfileNotFound404() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        UserClient client = newClient(holder);
        holder[0].expect(requestTo("http://user-service:8082/api/v1/users/me"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"success\":false,\"message\":\"Profile not found\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThat(client.me("Bearer t")).isNull();
    }

    @Test
    void me_wrapsServerErrorAsUpstreamException() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        UserClient client = newClient(holder);
        holder[0].expect(requestTo("http://user-service:8082/api/v1/users/me"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.me("Bearer t"))
                .isInstanceOf(UpstreamException.class);
    }
}
