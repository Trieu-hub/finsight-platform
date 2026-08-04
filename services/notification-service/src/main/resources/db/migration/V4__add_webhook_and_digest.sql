-- Two additions to how an alert leaves this service: an outbound webhook, and digest batching.
--
-- Both hang off the row that already answers "how does this user want to be reached", rather than
-- getting tables of their own: a user has one destination per channel, so a second table would buy
-- nothing but a join.
ALTER TABLE notification_preferences
    -- IMMEDIATE | HOURLY | DAILY. VARCHAR, not MySQL's ENUM, for the same reason
    -- notifications.severity is a VARCHAR: adding a mode later is a code change, not an ALTER on a
    -- live table, and the value reads the same in a dump as it does in Java.
    ADD COLUMN digest_mode     VARCHAR(16)   NOT NULL DEFAULT 'IMMEDIATE',
    -- NULL until the user sets one. 2048 is the practical URL ceiling browsers and proxies agree on.
    ADD COLUMN webhook_url     VARCHAR(2048)     NULL,
    ADD COLUMN webhook_enabled BOOLEAN       NOT NULL DEFAULT FALSE,
    -- The HMAC key the receiver verifies with. Stored in the clear on purpose: signing needs the
    -- raw key, so this is a shared secret like an API key, not a password to be hashed. It is
    -- returned to the user exactly once, when it is generated.
    ADD COLUMN webhook_secret  VARCHAR(64)   NULL;

-- When this notification was accounted for by the content-carrying channels (email, webhook).
-- NULL means "still owed a delivery" and is what the digest scheduler looks for. Rows for users on
-- IMMEDIATE are stamped as they are created, so NULL never means "delivered ages ago".
ALTER TABLE notifications
    ADD COLUMN digested_at DATETIME(6) NULL;

-- Everything that already exists was delivered under the old immediate-only behaviour. Without
-- this backfill the first scheduler run would treat the entire history as a pending digest and
-- mail every user their whole notification archive.
UPDATE notifications SET digested_at = created_at WHERE digested_at IS NULL;

-- The scheduler's only query is "which users have rows still owed a delivery". digested_at leads
-- because it is the selective half — in steady state almost every row is stamped.
CREATE INDEX idx_notifications_pending_digest ON notifications (digested_at, user_id);
