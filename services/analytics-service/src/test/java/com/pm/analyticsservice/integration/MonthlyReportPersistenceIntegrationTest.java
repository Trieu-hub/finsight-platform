package com.pm.analyticsservice.integration;

import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.entity.MonthlyReportSent;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.repository.MonthlyReportSentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The monthly report's persistence against real MySQL (Phase G.2): the V2 schema, its unique
 * constraint, and the one query in this service that crosses users. Booting the context also
 * proves Flyway and the entity mappings still agree — Hibernate runs in {@code validate} mode.
 */
class MonthlyReportPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final String JULY = "2026-07";

    @Autowired
    private MonthlyReportSentRepository sentRepository;
    @Autowired
    private MonthlyCategoryRollupRepository rollupRepository;

    @BeforeEach
    void clean() {
        sentRepository.deleteAll();
        rollupRepository.deleteAll();
    }

    @Test
    void recordsAndRecognisesAReportAlreadySent() {
        sentRepository.save(sent(42L, JULY));

        assertThat(sentRepository.existsByUserIdAndPeriodMonth(42L, JULY)).isTrue();
        // Another month, and another user, are both still owed one.
        assertThat(sentRepository.existsByUserIdAndPeriodMonth(42L, "2026-06")).isFalse();
        assertThat(sentRepository.existsByUserIdAndPeriodMonth(43L, JULY)).isFalse();
    }

    /** The backstop behind the scheduler's check: an email cannot be unsent. */
    @Test
    void refusesASecondReportForTheSameUserAndMonth() {
        sentRepository.save(sent(42L, JULY));

        assertThatThrownBy(() -> sentRepository.saveAndFlush(sent(42L, JULY)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsEachUserWithActivityInTheMonthOnce() {
        // Two rows for user 42 — the report goes to a user, not to a category.
        rollupRepository.save(rollup(42L, JULY, 4L, "EXPENSE"));
        rollupRepository.save(rollup(42L, JULY, 1L, "INCOME"));
        rollupRepository.save(rollup(43L, JULY, 4L, "EXPENSE"));
        rollupRepository.save(rollup(44L, "2026-06", 4L, "EXPENSE"));

        assertThat(rollupRepository.findUserIdsWithActivityIn(JULY))
                .containsExactlyInAnyOrder(42L, 43L);
    }

    private static MonthlyReportSent sent(long userId, String periodMonth) {
        return MonthlyReportSent.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .periodMonth(periodMonth)
                .sentAt(LocalDateTime.now())
                .build();
    }

    private static MonthlyCategoryRollup rollup(long userId, String periodMonth, long categoryId,
                                                String type) {
        return MonthlyCategoryRollup.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .yearMonth(periodMonth)
                .categoryId(categoryId)
                .type(type)
                .currency("VND")
                .totalAmount(new BigDecimal("1000000"))
                .txnCount(1)
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
