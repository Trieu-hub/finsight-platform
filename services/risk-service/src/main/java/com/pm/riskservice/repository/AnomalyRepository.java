package com.pm.riskservice.repository;

import com.pm.riskservice.entity.Anomaly;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence + read queries for {@link Anomaly} (Phase F.1). Listing returns newest
 * detections first (backs GET /api/v1/anomalies).
 */
public interface AnomalyRepository extends JpaRepository<Anomaly, UUID> {

    List<Anomaly> findAllByOrderByOccurredAtDesc();
}
