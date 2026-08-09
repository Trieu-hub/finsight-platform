package com.pm.riskservice.web;

import com.pm.riskservice.repository.RecurringSeriesRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API over detected recurring charges (Phase G.1).
 *
 * <p>Like the risk, insight and anomaly read APIs this is deliberately unauthenticated and
 * unscoped: risk-service carries no JWT stack and is not exposed through the gateway, so this
 * is an internal/admin read surface returning every series. It is what makes the detector
 * inspectable on a running box — the user-facing surface is the alert, not this.
 */
@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private final RecurringSeriesRepository repository;

    public RecurringController(RecurringSeriesRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<RecurringSeriesResponse> list() {
        return repository.findAllByOrderByNextExpectedDesc().stream()
                .map(RecurringSeriesResponse::from)
                .toList();
    }
}
