-- created_at/updated_at on users are now managed by JPA auditing (AuditingConfig).
-- Add the updated_at column and backfill existing rows so it is never null for them.
ALTER TABLE users
    ADD COLUMN updated_at TIMESTAMP NULL AFTER created_at;

UPDATE users SET updated_at = created_at WHERE updated_at IS NULL;
