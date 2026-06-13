package com.pm.riskservice.web;

import com.pm.riskservice.service.RiskAlertService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Read API over persisted risk alerts (Phase D.2).
 *
 * <p>Deliberately unauthenticated and unscoped: risk-service carries no JWT stack (see
 * Phase D.1) and is not exposed through the gateway, so this is an internal/admin read
 * surface returning all alerts. Adding JWT validation and per-user scoping is a later
 * hardening step, not part of D.2.
 */
@RestController
@RequestMapping("/api/v1/risks")
public class RiskAlertController {

    private final RiskAlertService riskAlertService;

    public RiskAlertController(RiskAlertService riskAlertService) {
        this.riskAlertService = riskAlertService;
    }

    @GetMapping
    public List<RiskAlertResponse> list() {
        return riskAlertService.findAll().stream()
                .map(RiskAlertResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public RiskAlertResponse getById(@PathVariable UUID id) {
        return riskAlertService.findById(id)
                .map(RiskAlertResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Risk alert not found: " + id));
    }
}
