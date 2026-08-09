package com.pm.analyticsservice;

import com.pm.analyticsservice.security.jwt.JwtProperties;
import com.pm.analyticsservice.summarizer.SummarizerAiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} exists for one job: {@code MonthlyReportScheduler} (Phase G.2).
 * "The month ended" is not an event any service publishes, so the report that follows it has
 * to be swept for.
 */
@EnableScheduling
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties({JwtProperties.class, SummarizerAiProperties.class})
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
