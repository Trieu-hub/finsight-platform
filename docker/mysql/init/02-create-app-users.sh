#!/bin/bash
# Runs once on first MySQL container init, after 01-create-databases.sql.
#
# Creates one least-privilege account per service, each scoped to ONLY its own
# database. Passwords come from environment variables (see docker-compose.yml /
# .env) so no credential is ever written to source control.
#
# GRANT ALL (rather than just SELECT/INSERT/UPDATE/DELETE) on the single schema is
# intentional: Flyway runs as the application's own DB user and needs DDL
# (CREATE/ALTER/DROP/INDEX/REFERENCES) to apply migrations. The privilege is still
# bounded to that one database — auth_user cannot touch budget_db, etc.
#
# NOTE: init scripts only run when the data directory is empty (first start). For an
# existing volume, recreate it (`docker compose down -v`) or run these statements
# manually as root.
set -euo pipefail

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS 'auth_user'@'%'        IDENTIFIED BY '${AUTH_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'user_user'@'%'        IDENTIFIED BY '${USER_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'transaction_user'@'%' IDENTIFIED BY '${TRANSACTION_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'budget_user'@'%'      IDENTIFIED BY '${BUDGET_DB_PASSWORD}';

GRANT ALL PRIVILEGES ON auth_db.*        TO 'auth_user'@'%';
GRANT ALL PRIVILEGES ON user_db.*        TO 'user_user'@'%';
GRANT ALL PRIVILEGES ON transaction_db.* TO 'transaction_user'@'%';
GRANT ALL PRIVILEGES ON budget_db.*      TO 'budget_user'@'%';

FLUSH PRIVILEGES;
SQL

echo "[02-create-app-users] created per-service least-privilege DB users"
