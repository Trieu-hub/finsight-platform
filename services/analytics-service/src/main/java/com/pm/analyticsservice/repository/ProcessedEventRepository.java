package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Idempotency inbox access (see {@link ProcessedEvent}). */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
