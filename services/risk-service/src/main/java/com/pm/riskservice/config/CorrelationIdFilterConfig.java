package com.pm.riskservice.config;

import com.pm.riskservice.logging.CorrelationIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link CorrelationIdFilter} at HIGHEST_PRECEDENCE so the correlation id is in the
 * MDC for every log line of a request. This service is internal and carries no security filter
 * chain, but its controllers are still reached over HTTP from inside the compose network, and
 * those calls should stay traceable like any other.
 */
@Configuration
public class CorrelationIdFilterConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
