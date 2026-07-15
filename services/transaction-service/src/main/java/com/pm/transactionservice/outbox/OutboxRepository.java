package com.pm.transactionservice.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The oldest pending events, id-ascending, capped by {@code pageable}'s size. Publishing in
     * this order preserves per-user ordering (events for a user are keyed to one partition).
     */
    @Query("select o from OutboxEvent o order by o.id asc")
    List<OutboxEvent> findBatch(Pageable pageable);
}
