package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.BudgetDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.exception.UpstreamException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/** Reads the caller's budget definitions from budget-service, relaying the JWT. */
@Component
public class BudgetClient {

    private static final ParameterizedTypeReference<UpstreamApiResponse<List<BudgetDto>>> BUDGET_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    public BudgetClient(RestClient.Builder builder, DashboardProperties properties) {
        this.client = builder.baseUrl(properties.getServices().getBudgetUri()).build();
    }

    public List<BudgetDto> listBudgets(String authorization) {
        try {
            UpstreamApiResponse<List<BudgetDto>> body = client.get()
                    .uri("/api/v1/budgets")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(BUDGET_LIST);
            return (body == null || body.data() == null) ? List.of() : body.data();
        } catch (RestClientException e) {
            throw new UpstreamException("budget-service", e);
        }
    }
}
