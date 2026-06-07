package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.TrendPointDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.exception.UpstreamException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransactionClientTest {

    private TransactionClient newClient(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        DashboardProperties props = new DashboardProperties();
        props.getServices().setTransactionUri("http://transaction-service:8083");
        return new TransactionClient(builder, props);
    }

    @Test
    void trend_unwrapsEnvelope_relaysJwt_andSendsDateRange() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        TransactionClient client = newClient(holder);
        // 'meta' is an unknown field that must be ignored.
        String json = """
                {"success":true,"data":[
                  {"date":"2026-06-05","income":200,"expense":80,"balance":120},
                  {"date":"2026-06-06","income":10,"expense":10,"balance":0}
                ],"meta":{}}
                """;
        holder[0].expect(requestTo(
                        "http://transaction-service:8083/api/v1/transactions/summary/trend?fromDate=2026-06-01&toDate=2026-06-30"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer t"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<TrendPointDto> points = client.trend("Bearer t", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(points).hasSize(2);
        assertThat(points.get(0).date()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(points.get(0).income()).isEqualByComparingTo("200");
        assertThat(points.get(0).balance()).isEqualByComparingTo("120");
        holder[0].verify();
    }

    @Test
    void trend_nullData_returnsEmptyList() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        TransactionClient client = newClient(holder);
        holder[0].expect(requestTo(
                        "http://transaction-service:8083/api/v1/transactions/summary/trend?fromDate=2026-06-01&toDate=2026-06-30"))
                .andRespond(withSuccess("{\"success\":true,\"data\":null}", MediaType.APPLICATION_JSON));

        assertThat(client.trend("Bearer t", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))).isEmpty();
    }

    @Test
    void trend_wrapsUpstreamErrorAsUpstreamException() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        TransactionClient client = newClient(holder);
        holder[0].expect(requestTo(
                        "http://transaction-service:8083/api/v1/transactions/summary/trend?fromDate=2026-06-01&toDate=2026-06-30"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.trend("Bearer t", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .isInstanceOf(UpstreamException.class);
    }
}
