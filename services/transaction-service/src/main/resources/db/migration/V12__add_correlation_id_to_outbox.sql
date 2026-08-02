-- Carries the request's correlation id with the event. The relay publishes asynchronously, off the
-- request thread, so the id cannot be read from the MDC at publish time — it has to be persisted
-- alongside the event and replayed onto the Kafka record header (see OutboxWriter/OutboxRelay).
-- Nullable: rows written before this migration have no id, and an event produced outside a request
-- (none today) legitimately has none either.
ALTER TABLE outbox ADD COLUMN correlation_id VARCHAR(64) NULL AFTER payload;
