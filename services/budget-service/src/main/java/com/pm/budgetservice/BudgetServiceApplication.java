package com.pm.budgetservice;

import com.pm.budgetservice.security.jwt.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties(JwtProperties.class)
public class BudgetServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BudgetServiceApplication.class, args);
    }
}
