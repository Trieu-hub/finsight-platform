package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.BudgetDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.exception.UpstreamException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BudgetClientTest {

    private BudgetClient newClient(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        DashboardProperties props = new DashboardProperties();
        props.getServices().setBudgetUri("http://budget-service:8084");
        return new BudgetClient(builder, props);
    }

    @Test
    void listBudgets_unwrapsEnvelope_andRelaysJwt() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        BudgetClient client = newClient(holder);
        // 'meta' is an unknown field that must be ignored.
        String json = """
                {"success":true,"data":[
                  {"id":"11111111-1111-1111-1111-111111111111","name":"Food","categoryId":10,
                   "periodType":"MONTHLY","startDate":"2026-06-01","endDate":"2026-06-30",
                   "limitAmount":200,"currency":"USD","userId":1}
                ],"meta":{"page":1,"limit":10,"total":1}}
                """;
        holder[0].expect(requestTo("http://budget-service:8084/api/v1/budgets"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer t"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<BudgetDto> budgets = client.listBudgets("Bearer t");

        assertThat(budgets).hasSize(1);
        assertThat(budgets.get(0).categoryId()).isEqualTo(10L);
        assertThat(budgets.get(0).limitAmount()).isEqualByComparingTo("200");
        holder[0].verify();
    }

    @Test
    void listBudgets_wrapsUpstreamErrorAsUpstreamException() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        BudgetClient client = newClient(holder);
        holder[0].expect(requestTo("http://budget-service:8084/api/v1/budgets"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.listBudgets("Bearer t"))
                .isInstanceOf(UpstreamException.class);
    }
}
