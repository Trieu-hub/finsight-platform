-- LuckyMe games play with the user's real (fake-money) wallet balance: every round settles to
-- ONE net transaction, so wins and losses show up in the dashboard, budgets and analytics like
-- any other money movement. Two dedicated categories keep that activity separable from the
-- user's genuine spending instead of polluting 'Entertainment'.
-- Ids 1-10 are the V2 seed and 11 is V5's system 'Transfer'; 12 is the next free id. Marked
-- is_system like Transfer, so a user cannot rename or delete a category the game writes to.
INSERT IGNORE INTO categories (id, name, type, is_system) VALUES
    (12, 'Games',    'EXPENSE', TRUE),
    (13, 'Winnings', 'INCOME',  TRUE);

-- A user whose wallet goes negative from playing is locked out for a while. The lockout is
-- server-side so clearing localStorage does not buy another spin. Rows are append-only: the
-- active ban is the one with the greatest banned_until, and the history drives the escalation
-- (a repeat offender is banned for longer at the same debt).
CREATE TABLE IF NOT EXISTS game_bans (
    id           BIGINT         NOT NULL AUTO_INCREMENT,
    user_id      BIGINT         NOT NULL,
    -- Debt (a positive number) at the moment the ban was applied.
    debt         DECIMAL(19, 4) NOT NULL,
    -- Human-readable tier that produced the duration, e.g. 'FIVE_MINUTES', 'ONE_DAY'.
    tier         VARCHAR(20)    NOT NULL,
    banned_at    DATETIME(6)    NOT NULL,
    banned_until DATETIME(6)    NOT NULL,
    CONSTRAINT pk_game_bans PRIMARY KEY (id),
    KEY idx_game_bans_user_until (user_id, banned_until)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
