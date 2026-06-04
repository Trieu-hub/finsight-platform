package com.pm.budgetservice.integration;

import com.pm.budgetservice.entity.Budget;
import com.pm.budgetservice.enums.BudgetPeriod;
import com.pm.budgetservice.repository.BudgetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the MySQL alignment end-to-end against a real container:
 * Flyway migrations apply, Hibernate {@code ddl-auto=validate} accepts the schema,
 * the UUID (CHAR(36)) primary key round-trips, auditing populates timestamps, and the
 * soft-delete query contract holds.
 */
class BudgetPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private BudgetRepository budgetRepository;

    @Test
    void savesAndFetchesBudget() {
        UUID id = UUID.randomUUID();
        long userId = 1001L;

        Budget budget = Budget.builder()
                .id(id)
                .userId(userId)
                .name("Groceries")
                .categoryId(4L)
                .periodType(BudgetPeriod.MONTHLY)
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .limitAmount(new BigDecimal("500.0000"))
                .currency("USD")
                .isDeleted(false)
                .build();

        budgetRepository.save(budget);

        Optional<Budget> found =
                budgetRepository.findByIdAndUserIdAndIsDeletedFalse(id, userId);

        assertThat(found).isPresent();
        Budget loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getLimitAmount()).isEqualByComparingTo("500.0000");
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getPeriodType()).isEqualTo(BudgetPeriod.MONTHLY);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void softDeletedBudgetIsHiddenFromTheOwnerQuery() {
        UUID id = UUID.randomUUID();
        long userId = 2002L;

        Budget budget = Budget.builder()
                .id(id)
                .userId(userId)
                .categoryId(1L)
                .periodType(BudgetPeriod.YEARLY)
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .limitAmount(new BigDecimal("12000.0000"))
                .currency("EUR")
                .isDeleted(false)
                .build();
        budgetRepository.save(budget);

        budget.setDeleted(true);
        budgetRepository.save(budget);

        assertThat(budgetRepository.findByIdAndUserIdAndIsDeletedFalse(id, userId))
                .isEmpty();
        assertThat(budgetRepository.findById(id)).isPresent();
    }

    @Test
    void duplicateGuardIgnoresSoftDeletedRows() {
        long userId = 3003L;
        Budget active = Budget.builder()
                .id(UUID.randomUUID()).userId(userId).categoryId(4L)
                .periodType(BudgetPeriod.MONTHLY)
                .startDate(LocalDate.of(2026, 8, 1)).endDate(LocalDate.of(2026, 8, 31))
                .limitAmount(new BigDecimal("100.0000")).currency("USD").isDeleted(true)
                .build();
        budgetRepository.save(active);

        // The only matching row is soft-deleted, so the slot is reported free.
        boolean exists = budgetRepository
                .existsByUserIdAndCategoryIdAndPeriodTypeAndStartDateAndIsDeletedFalse(
                        userId, 4L, BudgetPeriod.MONTHLY, LocalDate.of(2026, 8, 1));
        assertThat(exists).isFalse();
    }
}
