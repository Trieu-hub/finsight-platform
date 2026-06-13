package com.pm.riskservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Risk Intelligence MVP (Phase D.1). A stateless stream processor: it consumes
 * {@code TransactionCreated} events, evaluates a single rule, and publishes
 * {@code RiskDetected} when the rule fires. It owns no database and exposes no REST
 * API — the only inbound/outbound paths are Kafka topics.
 */
@SpringBootApplication
public class RiskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }
}
