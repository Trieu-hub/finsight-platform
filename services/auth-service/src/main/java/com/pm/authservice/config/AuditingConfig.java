package com.pm.authservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables JPA auditing so {@code @CreatedDate}/{@code @LastModifiedDate} on entities
 * are populated automatically — consistent with the other FinSight services.
 */
@Configuration
@EnableJpaAuditing
public class AuditingConfig {
}
