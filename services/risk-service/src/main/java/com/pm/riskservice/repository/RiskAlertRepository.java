package com.pm.riskservice.repository;

import com.pm.riskservice.entity.RiskAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link RiskAlert}. Listing returns newest detections first.
 */
public interface RiskAlertRepository extends JpaRepository<RiskAlert, UUID> {

    List<RiskAlert> findAllByOrderByOccurredAtDesc();
}
