-- When each account last signed in.
--
-- Why a column and not just a metric: a counter answers "how many logins yesterday", which is not
-- the question anyone actually asks. "How many distinct people used this in the last week" needs
-- per-user state, and nothing here had any — the only trace of a session was a Redis refresh
-- token, which disappears when it expires and takes the evidence with it.
--
-- NULL means "has not signed in since this column existed", not "never signed in": every row
-- predates the column. Do not read a NULL as an inactive account until the platform has been
-- running with this for a while.
ALTER TABLE users
    ADD COLUMN last_login_at DATETIME(6) NULL;
