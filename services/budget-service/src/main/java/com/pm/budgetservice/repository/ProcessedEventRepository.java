package com.pm.budgetservice.repository;

import com.pm.budgetservice.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Idempotency inbox access (see {@link ProcessedEvent}). */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
