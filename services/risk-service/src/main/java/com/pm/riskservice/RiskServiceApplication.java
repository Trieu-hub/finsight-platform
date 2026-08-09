package com.pm.riskservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Risk Intelligence MVP (Phase D.1). A stateless stream processor: it consumes
 * {@code TransactionCreated} events, evaluates a single rule, and publishes
 * {@code RiskDetected} when the rule fires. It owns no database and exposes no REST
 * API — the only inbound/outbound paths are Kafka topics.
 *
 * <p>{@code @EnableScheduling} exists for one job: {@code RecurringSweeper} (Phase G.1), which
 * reports a recurring charge that never arrived. That signal is an absence, so unlike every
 * other rule here it cannot be derived from a consumed event.
 */
@EnableScheduling
@SpringBootApplication
public class RiskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }
}
