package com.pm.transactionservice.service.impl;

import com.pm.transactionservice.dto.TransactionFilterRequest;
import com.pm.transactionservice.entity.Category;
import com.pm.transactionservice.entity.Transaction;
import com.pm.transactionservice.export.TransactionCsvWriter;
import com.pm.transactionservice.repository.CategoryRepository;
import com.pm.transactionservice.repository.TransactionRepository;
import com.pm.transactionservice.repository.TransactionSpecifications;
import com.pm.transactionservice.service.TransactionExportService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads a user's transactions out as CSV — the counterpart to the statement import.
 *
 * <p>Separate from {@code TransactionServiceImpl} on purpose: this writes nothing, publishes no
 * event and touches no wallet. It reuses that service's {@link TransactionSpecifications} so an
 * export can never disagree with the list the user is looking at, including the soft-delete
 * filter.
 */
@Service
public class TransactionExportServiceImpl implements TransactionExportService {

    /**
     * The most rows one export will contain. An export is one HTTP response held in memory, so it
     * needs a bound; 10,000 transactions is more than a personal-finance user accumulates in
     * years, and the date filter is there for anyone who needs to go past it. Newest first, so a
     * user who does hit the cap gets the period they most likely wanted.
     */
    static final int MAX_ROWS = 10_000;

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionCsvWriter csvWriter;

    public TransactionExportServiceImpl(TransactionRepository transactionRepository,
                                        CategoryRepository categoryRepository,
                                        TransactionCsvWriter csvWriter) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.csvWriter = csvWriter;
    }

    @Override
    @Transactional(readOnly = true)
    public String exportCsv(Long userId, TransactionFilterRequest filter) {
        Sort sort = Sort.by(Sort.Direction.DESC, "transactionDate")
                .and(Sort.by(Sort.Direction.ASC, "id"));
        Specification<Transaction> spec =
                TransactionSpecifications.forUserWithFilters(userId, filter);
        List<Transaction> rows = transactionRepository
                .findAll(spec, PageRequest.of(0, MAX_ROWS, sort))
                .getContent();
        return csvWriter.write(rows, categoryNames());
    }

    /**
     * Category id → name for the whole (small, seeded, shared) category table. One query beats a
     * lookup per row, and the table is bounded in a way the transaction table is not.
     */
    private Map<Long, String> categoryNames() {
        return categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName,
                        (first, second) -> first));
    }
}
