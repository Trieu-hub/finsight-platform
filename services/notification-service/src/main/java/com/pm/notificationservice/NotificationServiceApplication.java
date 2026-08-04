package com.pm.notificationservice;

import com.pm.notificationservice.email.EmailProperties;
import com.pm.notificationservice.narrator.NarratorAiProperties;
import com.pm.notificationservice.push.PushProperties;
import com.pm.notificationservice.security.jwt.JwtProperties;
import com.pm.notificationservice.webhook.WebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling drives the SSE heartbeat that keeps idle notification streams from being culled
// by intermediary proxies, and the digest scheduler that sends batched email/webhook deliveries.
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
@EnableConfigurationProperties({JwtProperties.class, NarratorAiProperties.class, PushProperties.class,
        EmailProperties.class, WebhookProperties.class})
@EnableScheduling
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
