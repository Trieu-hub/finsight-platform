-- Web push delivery. Until now a notification only reached a user who had the app open (the
-- bell + the SSE stream); this table is what lets one reach a closed tab.
--
-- The row belongs to a *browser*, not to a user: one user commonly has several (laptop, phone),
-- and the same browser gets a brand-new endpoint whenever the subscription is renewed. So the
-- endpoint — not the user — is the natural key, and a re-subscribe replaces its row instead of
-- accumulating dead ones.
CREATE TABLE IF NOT EXISTS push_subscriptions (
    id          CHAR(36)     NOT NULL,
    user_id     BIGINT       NOT NULL,
    -- The push service URL handed over by the browser (fcm.googleapis.com for Chrome,
    -- updates.push.services.mozilla.com for Firefox, ...). This is where the push is POSTed.
    endpoint    VARCHAR(512) NOT NULL,
    -- The subscription's client public key and auth secret. Unused while the pushes we send
    -- carry no payload; stored because encrypting one (RFC 8291) needs exactly these two, and
    -- they cannot be recovered later without asking the browser to re-subscribe.
    p256dh      VARCHAR(255) NOT NULL,
    auth_secret VARCHAR(255) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_push_subscriptions PRIMARY KEY (id),
    -- 512 utf8mb4 chars = 2048 bytes, inside InnoDB's 3072-byte index limit.
    CONSTRAINT uq_push_subscriptions_endpoint UNIQUE (endpoint)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- The only read path is "every browser belonging to this user", run once per notification.
CREATE INDEX idx_push_subscriptions_user ON push_subscriptions (user_id);
