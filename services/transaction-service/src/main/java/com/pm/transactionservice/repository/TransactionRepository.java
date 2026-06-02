package com.pm.transactionservice.repository;

import com.pm.transactionservice.entity.Transaction;
import com.pm.transactionservice.repository.projection.CategoryAggregate;
import com.pm.transactionservice.repository.projection.DailyTypeAggregate;
import com.pm.transactionservice.repository.projection.TypeAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DB access only. Filtering/pagination is expressed through
 * {@link JpaSpecificationExecutor} + {@link TransactionSpecifications}.
 */
public interface TransactionRepository
        extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    /** Fetch a single non-deleted transaction owned by the given user. */
    Optional<Transaction> findByIdAndUserIdAndIsDeletedFalse(UUID id, Long userId);

    // Aliases avoid HQL-reserved words (type, count, date) so they parse as result names.

    /** Income/expense totals for a user within an inclusive date range. */
    @Query("""
            select t.type as transactionType, sum(t.amount) as total
            from Transaction t
            where t.userId = :userId and t.isDeleted = false
              and t.transactionDate between :from and :to
            group by t.type
            """)
    List<TypeAggregate> sumByType(@Param("userId") Long userId,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);

    /** Per-category totals for a user within an inclusive date range, richest first. */
    @Query("""
            select t.categoryId as categoryId, c.name as categoryName, c.type as transactionType,
                   sum(t.amount) as total, count(t) as entryCount
            from Transaction t, Category c
            where c.id = t.categoryId
              and t.userId = :userId and t.isDeleted = false
              and t.transactionDate between :from and :to
            group by t.categoryId, c.name, c.type
            order by sum(t.amount) desc
            """)
    List<CategoryAggregate> sumByCategory(@Param("userId") Long userId,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /** Daily per-type totals for a user within an inclusive date range, oldest first. */
    @Query("""
            select t.transactionDate as entryDate, t.type as transactionType, sum(t.amount) as total
            from Transaction t
            where t.userId = :userId and t.isDeleted = false
              and t.transactionDate between :from and :to
            group by t.transactionDate, t.type
            order by t.transactionDate asc
            """)
    List<DailyTypeAggregate> sumDailyByType(@Param("userId") Long userId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    /** True if any transaction (deleted or not) references the category — blocks delete. */
    boolean existsByCategoryId(Long categoryId);
}
