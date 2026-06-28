package com.pm.analyticsservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for analytics-service. Documents the service and declares the
 * bearer-JWT security scheme so Swagger UI's "Authorize" works. Endpoints and DTO
 * schemas are introspected automatically by springdoc.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI analyticsServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinSight Analytics Service API")
                        .description("Per-user spending analytics built from a Kafka rollup read "
                                + "model: month-over-month overview, category breakdown, spend "
                                + "forecast, and an optional AI monthly summary. All endpoints "
                                + "require a Bearer JWT and are scoped to the caller.")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
