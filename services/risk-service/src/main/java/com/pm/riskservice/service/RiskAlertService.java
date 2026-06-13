package com.pm.riskservice.service;

import com.pm.riskservice.entity.RiskAlert;
import com.pm.riskservice.event.RiskDetectedEvent;
import com.pm.riskservice.repository.RiskAlertRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records detected risks and serves the read API over them. The consumer calls
 * {@link #record(RiskDetectedEvent)} on detection; the controller uses the query methods.
 */
@Service
public class RiskAlertService {

    private final RiskAlertRepository repository;

    public RiskAlertService(RiskAlertRepository repository) {
        this.repository = repository;
    }

    /** Persists an alert mirroring the published {@code RiskDetected} event. */
    public RiskAlert record(RiskDetectedEvent event) {
        RiskAlert alert = new RiskAlert(
                event.eventId(),
                event.userId(),
                event.transactionId(),
                event.riskType(),
                event.riskSeverity(),
                Instant.parse(event.occurredAt()),
                Instant.now());
        return repository.save(alert);
    }

    /** All alerts, newest detection first. */
    public List<RiskAlert> findAll() {
        return repository.findAllByOrderByOccurredAtDesc();
    }

    public Optional<RiskAlert> findById(UUID id) {
        return repository.findById(id);
    }
}
