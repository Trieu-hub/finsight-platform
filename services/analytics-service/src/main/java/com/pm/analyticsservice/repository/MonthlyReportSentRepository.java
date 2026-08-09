package com.pm.analyticsservice.repository;

import com.pm.analyticsservice.entity.MonthlyReportSent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Which users have already been sent which monthly report (Phase G.2). */
public interface MonthlyReportSentRepository extends JpaRepository<MonthlyReportSent, UUID> {

    boolean existsByUserIdAndPeriodMonth(Long userId, String periodMonth);
}
