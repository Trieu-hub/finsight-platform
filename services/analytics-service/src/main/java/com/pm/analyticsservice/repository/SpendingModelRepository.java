package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.entity.SpendingModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpendingModelRepository extends JpaRepository<SpendingModel, UUID> {

    Optional<SpendingModel> findByUserIdAndCurrency(Long userId, String currency);
}
