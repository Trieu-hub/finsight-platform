# user-service → MySQL Consolidation — Final Report

**Date:** 2026-06-06
**Objective:** Remove PostgreSQL from the platform; move user-service onto the shared
MySQL 8 instance using the established MySQL + Flyway pattern; validate the full stack.
**Outcome:** ✅ **Complete and validated.** All four services run on MySQL; PostgreSQL
is gone; user-service boots, migrates, and serves traffic end-to-end through the gateway.
**Phase 2 / authentication:** not started (per instruction).

---

## 1. What changed (Stage B)

| # | Area | File(s) | Change |
|---|------|---------|--------|
| 1 | Dependencies | `services/user-service/pom.xml` | Removed `org.postgresql:postgresql` + `com.h2database:h2`; added `com.mysql:mysql-connector-j`, **`org.flywaydb:flyway-mysql`** (the module whose absence caused the outage), Testcontainers BOM + `junit-jupiter` + `mysql`, and `spring-boot-webmvc-test`. |
| 2 | Runtime config | `application.yml` | Datasource → `jdbc:mysql://localhost:3306/user_db`, user `root`, driver `com.mysql.cj.jdbc.Driver`; added `hibernate.dialect: MySQLDialect` (fleet convention). |
| 3 | Flyway migration | `db/migration/V1__create_user_profiles.sql` | `TIMESTAMP` → `DATETIME(6)`; added `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`. (Safe to edit: never previously applied — `user_db` had never existed.) |
| 4 | Compose | `docker-compose.yml` | Deleted `postgres` service + `postgres_data` volume; user-service now `depends_on: mysql` with MySQL env (`DB_URL/DB_USERNAME/DB_PASSWORD`); updated topology comment. |
| 5 | DB init | `docker/mysql/init/01-create-databases.sql` | Added `CREATE DATABASE IF NOT EXISTS user_db ...`. |
| 6 | Tests | `src/test/...` | Removed all H2 usage; added `integration/AbstractMySqlIntegrationTest` (singleton MySQL 8 container), `application-test.yml`, `integration/support/JwtTestTokens`, and a real-DB HTTP CRUD test `UserProfileApiIntegrationTest`; converted the two booting tests onto the container. Pure-Mockito `UserProfileServiceTest` unchanged. |
| 7 | Docs | root `README.md`; `user-service/CLAUDE.md`; `user-service/ARCHITECTURE_SNAPSHOT.md` | Updated stack/topology/engine/env/config/schema references PostgreSQL → MySQL. |

**Infrastructure removed:** PostgreSQL 16 service, `postgres_data` volume (and the
stale runtime volume), the `postgresql` JDBC driver, and the H2 test dependency. No
PostgreSQL remains in the platform.

---

## 2. Validation (Stage C)

### 2.1 Unit + integration tests — `mvnw test` (user-service)
```
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0   — BUILD SUCCESS
```
Run against a **real MySQL 8 Testcontainer**: Flyway applied V1, `ddl-auto: validate`
passed (entity ↔ `DATETIME(6)`/`VARCHAR` schema match confirmed), and full HTTP CRUD
through the genuine JWT filter succeeded. H2 is no longer used anywhere.

### 2.2 Full Docker Compose startup
All containers healthy; **no PostgreSQL container exists**:

| Container | State | DB |
|-----------|-------|----|
| finsight-mysql | running (healthy) | hosts auth_db, **user_db**, transaction_db, budget_db |
| finsight-redis | running (healthy) | — |
| finsight-auth-service | UP (`/actuator/health`) | auth_db |
| **finsight-user-service** | **UP** (was crash-looping) | **user_db (MySQL)** |
| finsight-transaction-service | UP | transaction_db |
| finsight-budget-service | UP | budget_db |
| finsight-api-gateway | UP | — |

- The init script created `user_db` on a fresh MySQL volume; `SHOW DATABASES` lists all
  four; `user_db` contains `user_profiles` + `flyway_schema_history`.
- user-service log: *"Successfully applied 1 migration to schema `user_db`, now at
  version v1"* against `jdbc:mysql://mysql:3306/user_db (MySQL 8.4)`. The previous
  `Unsupported Database: PostgreSQL` crash is gone.

### 2.3 End-to-end through the gateway (`:8080`)
Register → login (auth) → call each service with the issued JWT:

| Path via :8080 | with token | no token |
|----------------|-----------|----------|
| `auth /api/v1/auth/me` | 200 | 401 |
| `user /api/v1/users/me` (before profile) | 404 | 401 |
| `transaction /api/v1/transactions` | 200 | 401 |
| `budget /api/v1/budgets` | 200 | 401 |

**user-service full CRUD through the gateway (valid JSON):** `POST /users/me` → **201**
(row persisted to MySQL), `GET /users/me` → **200** returning the stored profile. This
is the path that returned `503 SERVICE_UNAVAILABLE` before the fix — now fully working.

---

## 3. Issue found during validation (NOT a blocker, NOT from this change)

**Malformed JSON request body → user-service returns `500` instead of `400`.**
- **Where:** `user-service` `GlobalExceptionHandler` has no
  `@ExceptionHandler(HttpMessageNotReadableException.class)`, so an unparseable body
  falls through to the generic `handleGeneral(Exception)` → `500 "An unexpected error
  occurred"`. auth-service maps the same case to `400 MALFORMED_REQUEST`.
- **Reproduces directly on `:8082`** (not a gateway effect); the gateway relays the
  downstream 500 **faithfully** — gateway behaviour is correct.
- **Surfaced by the test harness:** PowerShell's inline `curl -d '{...}'` mangled the
  JSON (space-splitting), producing a malformed body. With well-formed JSON (file-based
  `--data @file`, and the way real clients send it) every request returns 201/200.
- **Relationship to this task:** none — the exception handler was untouched by the DB
  consolidation; this is a **pre-existing robustness gap**. Recorded for a future
  hardening pass (add an `HttpMessageNotReadableException` handler → 400, mirroring
  auth-service). **Not fixed here** (out of scope: DB consolidation only).

### Documentation note
Historical, dated user-service reports (`AUDIT_REPORT.md`, `PHASE1_HARDENING_REPORT.md`,
`PROGRESS.md`, `USER_SERVICE_PHASE1_SUMMARY.md`, `TECH_DEBT.md`) still mention
PostgreSQL; they are point-in-time snapshots and were intentionally left unchanged.
Separately, **`transaction-service`'s own `CLAUDE.md`/`README.md` wrongly describe
PostgreSQL/JSONB although its code is MySQL** — pre-existing stale docs, unrelated to
this task, flagged for a later cleanup.

---

## 4. Gateway Phase 1 readiness — reassessment

The original full-stack validation passed the gateway on every dimension but recorded
one **blocking** platform defect: user-service down (Finding #1). That is now resolved.
Re-checking the Phase 1 criteria against the consolidated stack:

| Phase 1 criterion | Status |
|-------------------|--------|
| All services healthy behind the edge | ✅ Now 4/4 services + gateway UP (was 3/4) |
| Route resolution to real containers | ✅ (unchanged; still correct) |
| Request forwarding (method/headers/body) | ✅ incl. user-service POST/GET with body |
| Status-code propagation | ✅ 200/401/404 relayed verbatim, incl. user-service; 500 relayed faithfully |
| Timeout → 504 SERVICE_TIMEOUT | ✅ (verified previously, 10s) |
| Service-unavailable → 503 | ✅ (verified previously) |

**Verdict: Gateway Phase 1 is now READY.** The blocking defect that previously held it
back is fixed; all four backends are reachable and behave correctly through `:8080`.

Recommended (non-blocking) before/with later phases:
- Add the gateway route for `/api/v1/categories` (transaction-service exposes it; the
  gateway currently 404s it) — Finding #3 from the original validation, still open.
- Add an `HttpMessageNotReadableException → 400` handler in user-service (§3).
- Application containers still lack compose `healthcheck`s; `depends_on` waits only for
  "started", not "ready" (observation from the original report).

---

## 5. Conclusion

PostgreSQL has been removed from FinSight; user-service now runs on the shared MySQL 8
instance using the same Flyway + Testcontainers pattern as auth/transaction/budget. The
stack builds, all services are healthy, and traffic flows end-to-end through the gateway.
The one issue found is a pre-existing, unrelated user-service exception-mapping gap that
does not affect normal operation. **Gateway Phase 1 is ready; Phase 2 has not been
started.**
