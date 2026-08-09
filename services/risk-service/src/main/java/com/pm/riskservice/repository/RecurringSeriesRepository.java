package com.pm.riskservice.repository;

import com.pm.riskservice.entity.RecurringSeries;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Persistence and lookups for {@link RecurringSeries} (Phase G.1). */
public interface RecurringSeriesRepository extends JpaRepository<RecurringSeries, UUID> {

    /**
     * The live series a charge of {@code amount} could belong to — those whose established price
     * is within the caller's tolerance of it — closest price first. The detector takes the first
     * whose cadence also fits.
     *
     * <p>The band is expressed as factors of the series' own price rather than of the incoming
     * amount, so "within 25% of what this charge normally costs" means exactly that. Ordered
     * rather than limited to one row because two series in a category can both fall inside the
     * band, and the nearer price is the better candidate.
     */
    @Query("""
            select s
            from RecurringSeries s
            where s.userId = :userId
              and s.categoryId = :categoryId
              and s.currency = :currency
              and s.status = 'ACTIVE'
              and :amount >= s.typicalAmount * :minFactor
              and :amount <= s.typicalAmount * :maxFactor
            order by abs(s.typicalAmount - :amount)
            """)
    List<RecurringSeries> findActiveMatches(@Param("userId") Long userId,
                                            @Param("categoryId") Long categoryId,
                                            @Param("currency") String currency,
                                            @Param("amount") BigDecimal amount,
                                            @Param("minFactor") BigDecimal minFactor,
                                            @Param("maxFactor") BigDecimal maxFactor);

    /**
     * Established series whose expected charge is overdue as of {@code cutoff} — the sweep's
     * only query. {@code minOccurrences} keeps a series that has been seen twice out of it:
     * two charges are enough to guess at a cadence, not enough to report one as missing.
     */
    @Query("""
            select s
            from RecurringSeries s
            where s.status = 'ACTIVE'
              and s.occurrences >= :minOccurrences
              and s.nextExpected < :cutoff
            order by s.nextExpected
            """)
    List<RecurringSeries> findOverdue(@Param("minOccurrences") int minOccurrences,
                                      @Param("cutoff") LocalDate cutoff);

    /** All series, newest activity first (backs the internal read API). */
    List<RecurringSeries> findAllByOrderByNextExpectedDesc();
}
