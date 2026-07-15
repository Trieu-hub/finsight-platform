package com.pm.transactionservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * A pending integration event, written to the {@code outbox} table inside the same DB transaction
 * as the business change it describes. The {@link com.pm.transactionservice.outbox.OutboxRelay}
 * publishes it to Kafka and deletes it, so this table only ever holds events not yet on the broker.
 *
 * <p>The auto-increment {@code id} gives the relay a stable, monotonic order to publish in — which,
 * because each event is keyed by {@code partitionKey} (userId), preserves per-user ordering on the
 * topic exactly as the direct producer did.
 */
@Entity
@Table(name = "outbox")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** The event serialized as JSON, sent to Kafka verbatim (raw string, no re-encoding). */
    @Column(nullable = false, length = 4000)
    private String payload;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
