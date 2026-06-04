package com.pm.budgetservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group D: confirms the schema is the MySQL one Flyway built — migrations applied,
 * the mandatory indexes exist, and a user-scoped filtered query still runs.
 */
class SchemaIndexIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationsAppliedSuccessfully() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        // V1 (schema) at minimum.
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void mandatoryIndexesExistOnBudgets() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'budgets'",
                String.class);

        assertThat(indexes).contains(
                "idx_budgets_user_id",
                "idx_budgets_user_category",
                "idx_budgets_user_dates");
    }

    @Test
    void userScopedFilteredQueryExecutesAgainstMigratedSchema() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM budgets "
                        + "WHERE user_id = ? AND is_deleted = FALSE "
                        + "AND start_date <= ? AND end_date >= ? AND category_id = ?",
                Integer.class, 999_999L, "2026-06-15", "2026-06-15", 4L);
        assertThat(count).isNotNull();
    }
}
