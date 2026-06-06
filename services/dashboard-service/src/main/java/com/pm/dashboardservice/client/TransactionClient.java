package com.pm.dashboardservice.client;

import com.pm.dashboardservice.client.dto.CategorySummaryDto;
import com.pm.dashboardservice.client.dto.MonthlySummaryDto;
import com.pm.dashboardservice.client.dto.TransactionDto;
import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.exception.UpstreamException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.List;

/** Reads spend summaries from transaction-service, relaying the JWT. */
@Component
public class TransactionClient {

    private static final ParameterizedTypeReference<UpstreamApiResponse<List<CategorySummaryDto>>> CATEGORY_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<UpstreamApiResponse<MonthlySummaryDto>> MONTHLY =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<UpstreamApiResponse<List<TransactionDto>>> TRANSACTION_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    public TransactionClient(RestClient.Builder builder, DashboardProperties properties) {
        this.client = builder.baseUrl(properties.getServices().getTransactionUri()).build();
    }

    public List<CategorySummaryDto> categorySummary(String authorization, LocalDate fromDate, LocalDate toDate) {
        try {
            UpstreamApiResponse<List<CategorySummaryDto>> body = client.get()
                    .uri(uri -> uri.path("/api/v1/transactions/summary/categories")
                            .queryParam("fromDate", fromDate)
                            .queryParam("toDate", toDate)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(CATEGORY_LIST);
            return (body == null || body.data() == null) ? List.of() : body.data();
        } catch (RestClientException e) {
            throw new UpstreamException("transaction-service", e);
        }
    }

    /** Most-recent transactions (page 1). Ordering (transactionDate DESC, id ASC) is
     *  enforced by transaction-service; {@code limit} is expected pre-clamped to 1..100. */
    public List<TransactionDto> recentTransactions(String authorization, int limit) {
        try {
            UpstreamApiResponse<List<TransactionDto>> body = client.get()
                    .uri(uri -> uri.path("/api/v1/transactions")
                            .queryParam("page", 1)
                            .queryParam("limit", limit)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(TRANSACTION_LIST);
            return (body == null || body.data() == null) ? List.of() : body.data();
        } catch (RestClientException e) {
            throw new UpstreamException("transaction-service", e);
        }
    }

    public MonthlySummaryDto monthly(String authorization, int year, int month) {
        try {
            UpstreamApiResponse<MonthlySummaryDto> body = client.get()
                    .uri(uri -> uri.path("/api/v1/transactions/summary/monthly")
                            .queryParam("year", year)
                            .queryParam("month", month)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(MONTHLY);
            return body == null ? null : body.data();
        } catch (RestClientException e) {
            throw new UpstreamException("transaction-service", e);
        }
    }
}
