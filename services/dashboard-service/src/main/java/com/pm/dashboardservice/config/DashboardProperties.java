package com.pm.dashboardservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upstream service base URIs and outbound HTTP timeouts. Externalized so the targets
 * can be repointed per environment (localhost for local runs, compose DNS in Docker).
 */
@ConfigurationProperties(prefix = "dashboard")
@Getter
@Setter
public class DashboardProperties {

    private Services services = new Services();
    private Timeouts timeouts = new Timeouts();

    @Getter
    @Setter
    public static class Services {
        private String userUri;
        private String transactionUri;
        private String budgetUri;
    }

    @Getter
    @Setter
    public static class Timeouts {
        /** Connection timeout for an upstream call (ms). */
        private long connectMs = 2000;
        /** Read timeout waiting on an upstream response (ms). */
        private long readMs = 5000;
    }
}
