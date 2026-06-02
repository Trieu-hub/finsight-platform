package com.pm.transactionservice.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group D: confirms the schema is the MySQL one Flyway built — migrations applied,
 * the four mandatory indexes exist, and a user-scoped filtered query still runs.
 */
class SchemaIndexIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationsAppliedSuccessfully() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        // V1 (schema) + V2 (seed) at minimum.
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }

    @Test
    void mandatoryIndexesExistOnTransactions() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'transactions'",
                String.class);

        assertThat(indexes).contains(
                "idx_transactions_user_id",
                "idx_transactions_user_date",
                "idx_transactions_user_type",
                "idx_transactions_category_id");
    }

    @Test
    void userScopedFilteredQueryExecutesAgainstMigratedSchema() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions "
                        + "WHERE user_id = ? AND is_deleted = FALSE "
                        + "AND transaction_date BETWEEN ? AND ? AND type = ?",
                Integer.class, 999_999L, "2026-01-01", "2026-12-31", "EXPENSE");
        assertThat(count).isNotNull();
    }
}
