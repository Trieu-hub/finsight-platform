-- Transactional outbox. A domain event is written to this table in the SAME DB transaction as
-- the transaction row it describes (see TransactionEventListener), so the two commit or roll back
-- together — closing the AFTER_COMMIT dual-write gap (ADR-0004) where a committed change could
-- fail to publish. A background relay (OutboxRelay) then reads rows in id order, publishes each to
-- Kafka, and deletes it on success. At-least-once delivery + idempotent consumers = effectively-once.
CREATE TABLE outbox (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id      VARCHAR(36)   NOT NULL,   -- domain eventId (dedup key downstream), for tracing
    topic         VARCHAR(255)  NOT NULL,   -- Kafka topic the relay publishes to
    partition_key VARCHAR(255)  NOT NULL,   -- userId as string → per-user partition ordering
    event_type    VARCHAR(100)  NOT NULL,   -- e.g. TransactionCreated
    payload       VARCHAR(4000) NOT NULL,   -- the event as JSON, sent to Kafka verbatim
    created_at    DATETIME(6)
);

-- The relay scans the oldest rows first (ORDER BY id). Rows are deleted once published, so the
-- table holds only in-flight events and the primary-key order scan stays cheap; no extra index.
