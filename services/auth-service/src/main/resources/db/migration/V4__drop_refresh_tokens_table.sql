-- Refresh tokens are now stored in Redis (see RefreshTokenService).
-- Drop the legacy MySQL table. DROP TABLE removes the table's own
-- fk_refresh_user foreign key automatically; no other table references it.
DROP TABLE IF EXISTS refresh_tokens;
