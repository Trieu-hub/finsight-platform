package com.pm.transactionservice.integration;

import com.pm.transactionservice.entity.Transaction;
import com.pm.transactionservice.enums.TransactionType;
import com.pm.transactionservice.repository.CategoryRepository;
import com.pm.transactionservice.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the MySQL alignment end-to-end against a real container:
 * Flyway migrations apply, Hibernate {@code ddl-auto=validate} accepts the schema,
 * the UUID (CHAR(36)) primary key and JSON metadata round-trip, and the soft-delete
 * query contract holds.
 */
class TransactionPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void flywaySeedsTheDefaultCategories() {
        // V2 seed ran against MySQL. >= 10 because categories are global and other
        // tests sharing this container may have created additional (user) categories.
        assertThat(categoryRepository.count()).isGreaterThanOrEqualTo(10);
        assertThat(categoryRepository.findById(4L)).isPresent();
    }

    @Test
    void savesAndFetchesTransactionWithJsonMetadata() {
        UUID id = UUID.randomUUID();
        long userId = 1001L;

        Transaction tx = Transaction.builder()
                .id(id)
                .userId(userId)
                .type(TransactionType.EXPENSE)
                .amount(new BigDecimal("42.5000"))
                .currency("USD")
                .categoryId(4L)
                .description("Lunch")
                .transactionDate(LocalDate.of(2026, 6, 1))
                .walletId(1L)
                .isDeleted(false)
                .metadata(Map.of("merchant", "Cafe"))
                .build();

        transactionRepository.save(tx);

        Optional<Transaction> found =
                transactionRepository.findByIdAndUserIdAndIsDeletedFalse(id, userId);

        assertThat(found).isPresent();
        Transaction loaded = found.get();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getAmount()).isEqualByComparingTo("42.5000");
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getMetadata()).containsEntry("merchant", "Cafe");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void softDeletedTransactionIsHiddenFromTheOwnerQuery() {
        UUID id = UUID.randomUUID();
        long userId = 2002L;

        Transaction tx = Transaction.builder()
                .id(id)
                .userId(userId)
                .type(TransactionType.INCOME)
                .amount(new BigDecimal("100.0000"))
                .currency("EUR")
                .categoryId(1L)
                .transactionDate(LocalDate.of(2026, 6, 2))
                .isDeleted(false)
                .build();
        transactionRepository.save(tx);

        tx.setDeleted(true);
        transactionRepository.save(tx);

        assertThat(transactionRepository.findByIdAndUserIdAndIsDeletedFalse(id, userId))
                .isEmpty();
        assertThat(transactionRepository.findById(id)).isPresent();
    }
}
