package com.pm.dashboardservice;

import com.pm.dashboardservice.config.DashboardProperties;
import com.pm.dashboardservice.security.jwt.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * FinSight Dashboard Service — a read-only aggregation layer / backend-for-frontend.
 *
 * <p>It owns no business data and no database. It composes read views by calling the
 * Transaction, Budget and User services over HTTP, relaying the caller's JWT so each
 * upstream authorizes as the same user. The token is also validated locally here
 * (every service validates its own tokens — the gateway does not replace that).
 */
// Exclude UserDetailsServiceAutoConfiguration: auth is entirely JWT-based, so Spring's
// default in-memory user (and its "Using generated security password" log) is unused noise.
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties({DashboardProperties.class, JwtProperties.class})
public class DashboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DashboardApplication.class, args);
    }
}
