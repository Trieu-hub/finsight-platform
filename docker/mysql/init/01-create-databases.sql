-- Runs once on first MySQL container init (/docker-entrypoint-initdb.d).
-- The single MySQL instance hosts all four MySQL-backed services; each service's
-- Flyway migrations own the schema *inside* its own database.
CREATE DATABASE IF NOT EXISTS auth_db        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS user_db        CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS transaction_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS budget_db      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
