-- Per-user delivery preferences, and the only place this service stores an email address.
--
-- Why the address lives here at all: notification-service is driven by Kafka, and RiskDetected
-- carries a userId, not a mailbox. It cannot ask auth-service for one either — no runtime
-- cross-service calls. So the address is captured from the caller's own JWT (auth-service signs
-- an `email` claim into it) at the moment the user turns email alerts on, and refreshed whenever
-- they toggle again. That keeps the DB-per-service boundary intact and makes the copy an explicit
-- consequence of opting in rather than a silent duplication of someone's PII.
CREATE TABLE IF NOT EXISTS notification_preferences (
    user_id       BIGINT       NOT NULL,
    -- NULL until the user opts in. Not unique: a shared mailbox is the user's business.
    email         VARCHAR(255)     NULL,
    email_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT pk_notification_preferences PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
