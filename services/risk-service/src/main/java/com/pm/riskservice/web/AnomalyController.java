package com.pm.riskservice.web;

import com.pm.riskservice.service.AnomalyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API over detected anomalies (Phase F.1).
 *
 * <p>Like the risk and insight read APIs this is deliberately unauthenticated and unscoped:
 * risk-service carries no JWT stack and is not exposed through the gateway, so this is an
 * internal/admin read surface returning all anomalies. Per-user scoping is later hardening.
 */
@RestController
@RequestMapping("/api/v1/anomalies")
public class AnomalyController {

    private final AnomalyService anomalyService;

    public AnomalyController(AnomalyService anomalyService) {
        this.anomalyService = anomalyService;
    }

    @GetMapping
    public List<AnomalyResponse> list() {
        return anomalyService.findAll().stream()
                .map(AnomalyResponse::from)
                .toList();
    }
}
