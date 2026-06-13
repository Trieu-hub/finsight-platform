package com.pm.riskservice.web;

import com.pm.riskservice.service.InsightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API over generated behavioral insights (Phase E.1).
 *
 * <p>Like the risk read API (Phase D.2) this is deliberately unauthenticated and unscoped:
 * risk-service carries no JWT stack and is not exposed through the gateway, so this is an
 * internal/admin read surface returning all insights. Per-user scoping is later hardening.
 */
@RestController
@RequestMapping("/api/v1/insights")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping
    public List<InsightResponse> list() {
        return insightService.findAll().stream()
                .map(InsightResponse::from)
                .toList();
    }
}
