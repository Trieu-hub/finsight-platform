-- notification-service schema (Phase G.1). Owns notification_db.
--
-- A notification is an in-app message generated for a user when an upstream event of
-- interest occurs. The first (and currently only) source is the RiskDetected event on
-- finsight.risk.detected: risk-service publishes it; this service consumes it and
-- materializes one notification row per detection.
CREATE TABLE IF NOT EXISTS notifications (
    id              CHAR(36)     NOT NULL,
    user_id         BIGINT       NOT NULL,
    -- Coarse classification of the notification (e.g. RISK_ALERT). Kept as a string,
    -- not an enum/FK, so new sources can be added without a schema change.
    type            VARCHAR(64)  NOT NULL,
    -- Severity copied from the source event (LOW/MEDIUM/HIGH/...); String for the same
    -- forward-compatibility reason as risk-service's RiskDetected contract.
    severity        VARCHAR(32)  NOT NULL,
    title           VARCHAR(160) NOT NULL,
    message         VARCHAR(512) NOT NULL,
    -- The source event's id (RiskDetected.eventId). Lets the UI/operator trace a
    -- notification back to the detection that produced it.
    source_event_id CHAR(36)         NULL,
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME(6)  NOT NULL,
    read_at         DATETIME(6)      NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- The two access patterns are both user-scoped: "my unread count" and "my recent
-- notifications, newest first". Both indexes lead with user_id so every query stays
-- bounded to one user's rows.
CREATE INDEX idx_notifications_user_unread  ON notifications (user_id, is_read);
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at);

-- Consumer-side idempotency inbox: one row per Kafka event already turned into a
-- notification. Kafka delivers at-least-once, so a redelivered RiskDetected must be
-- detected by its eventId and skipped instead of creating a duplicate notification.
-- The row is written in the SAME transaction as the notification insert.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
