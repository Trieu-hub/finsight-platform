package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.BudgetDto;
import com.pm.dashboardservice.config.DashboardProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Reads the caller's budget definitions from budget-service, relaying the JWT. Guarded by
 *  {@link UpstreamCalls} (circuit breaker + retry, and the UpstreamException mapping). */
@Component
public class BudgetClient {

    private static final ParameterizedTypeReference<UpstreamApiResponse<List<BudgetDto>>> BUDGET_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final UpstreamCalls upstreamCalls;

    public BudgetClient(RestClient.Builder builder, DashboardProperties properties,
                        UpstreamCalls upstreamCalls) {
        this.client = builder.baseUrl(properties.getServices().getBudgetUri()).build();
        this.upstreamCalls = upstreamCalls;
    }

    public List<BudgetDto> listBudgets(String authorization) {
        return upstreamCalls.call("budget-service", () -> {
            UpstreamApiResponse<List<BudgetDto>> body = client.get()
                    .uri("/api/v1/budgets")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(BUDGET_LIST);
            return (body == null || body.data() == null) ? List.of() : body.data();
        });
    }
}
