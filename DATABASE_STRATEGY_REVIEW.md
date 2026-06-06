# FinSight Database Strategy Review — user-service (PostgreSQL) vs the MySQL fleet

**Date:** 2026-06-06
**Trigger:** user-service crash-loops at startup (Flyway `Unsupported Database:
PostgreSQL 16.14`). Before fixing, this review decides *which* database user-service
should be on.
**Status:** Architecture review only — **no code changed, no dependencies added.**

---

## 1. Current-state assessment

### 1.1 The split
| Service | Engine | DB | Flyway dialect module | Driver | State |
|---------|--------|----|-----------------------|--------|-------|
| auth-service | MySQL 8 | auth_db | `flyway-mysql` ✅ | mysql-connector-j | healthy |
| transaction-service | MySQL 8 | transaction_db | `flyway-mysql` ✅ | mysql-connector-j | healthy |
| budget-service | MySQL 8 | budget_db | `flyway-mysql` ✅ | mysql-connector-j | healthy |
| **user-service** | **PostgreSQL 16** | user_db | **none** ❌ | postgresql | **crash-loop** |

Three services share **one** MySQL 8 instance (databases created by
`docker/mysql/init/01-create-databases.sql`). user-service alone runs a **second**
engine, a dedicated PostgreSQL 16 container (`POSTGRES_DB=user_db`).

### 1.2 Why it is currently broken
Flyway 10+ split each database's support into its own module. The three MySQL services
all carry `org.flywaydb:flyway-mysql`, with an explicit pom comment:
*"flyway-mysql adds MySQL support (required on Flyway 10+)."* user-service uses
`flyway-core` 11.14.1 but **never added the PostgreSQL analog
`flyway-database-postgresql`** — so Flyway cannot recognize the Postgres 16 connection
and aborts the Spring context.

### 1.3 Is PostgreSQL an intentional architecture decision, or drift?
**Verdict: nominally intentional, but architecturally unjustified — and implemented
incompletely. In practice it is drift dressed as a decision.**

Evidence it was *deliberately wired*:
- README lists it in the stack and topology diagram ("MySQL ... and PostgreSQL 16 (user)").
- A dedicated postgres service, volume, driver, and `application.yml` datasource exist.

Evidence it is **not a justified architectural decision**:
- **No stated rationale anywhere.** No CLAUDE.md/README sentence explains *why* user
  data needs PostgreSQL. Contrast with the README's other choices, which are each
  justified (no cross-service calls, `userId` sacred, UUID vs BIGINT PKs called out as
  "an intentional per-entity choice").
- **Zero PostgreSQL features are used.** The entire schema is one table with portable
  types only — `BIGINT, VARCHAR, DATE, TIMESTAMP` (see `V1__create_user_profiles.sql`).
  No `JSONB`, arrays, `SERIAL`/sequences, `gen_random_uuid()`, partial/expression
  indexes, full-text, GIS, or `CITEXT`. The PK is an **app-assigned `Long userId`** from
  the JWT — no identity/sequence generation at all.
- **Tests don't even use Postgres** — user-service tests run on H2 (the MySQL services
  use MySQL Testcontainers). So the Postgres dependency adds risk without test coverage.
- **The timeline points to oversight, not design.** First commits: auth-service
  2026-05-28 (MySQL, working flyway-mysql) → **user-service 2026-06-01 (Postgres, missing
  flyway module)** → transaction-service 2026-06-02 (MySQL, flyway-mysql present). The
  working MySQL+Flyway pattern existed *before* user-service, and the later MySQL services
  correctly carried `flyway-mysql`. user-service is the one service that diverged onto
  Postgres and simultaneously forgot the engine-specific Flyway module — the signature of
  a clone-and-tweak that was never run to completion against its new engine.

**Conclusion:** PostgreSQL here is *demonstrative polyglot persistence* — a second engine
introduced without a workload that needs it, and never finished. It is the source of the
current outage.

### 1.4 What PostgreSQL currently buys us
**Nothing functional.** Benefits realized today: none — no Postgres-specific type,
index, or capability is in use. The only thing it provides is "we can say the platform
is polyglot," which is a liability, not a benefit, at this stage.

---

## 2. Keep PostgreSQL vs migrate user-service to MySQL

### 2.1 Operational cost of running two engines (the status quo)
- **Two engines to run, patch, back up, monitor, and tune** for a 4-service platform —
  one of them serving a single, simple table.
- **Two sets of operational knowledge** (Postgres vs MySQL backup/restore, HA, perf
  tuning, connection pooling defaults, auth quirks).
- **Two failure modes & two upgrade cadences**; the gateway/stack is only as healthy as
  its weakest engine (today: 25% of the platform is down because of the second engine).
- **Inconsistent conventions** already creeping in: user-service omits the explicit
  `hibernate.dialect` that the MySQL services set, and uses H2 for tests instead of a
  real-engine Testcontainer — divergence that costs review/maintenance attention.
- **Extra container footprint** in every environment (local, CI, prod) for one table.

### 2.2 Option A — Keep PostgreSQL (just fix the bug)
- **Change:** add `org.flywaydb:flyway-database-postgresql` to user-service's pom.
- **Pros:** smallest possible diff; preserves a future option if user-service ever grows
  Postgres-specific needs (e.g. `JSONB` profile blobs, full-text search on bio).
- **Cons:** permanently pays the two-engine operational tax (§2.1) for benefits not used;
  keeps the fleet inconsistent; leaves a second engine + container in every environment;
  the "future need" is speculative.

### 2.3 Option B — Migrate user-service to MySQL
Bring user-service onto the shared MySQL 8 instance, matching its three siblings.

**Compatibility with existing Flyway migrations & JPA mappings — low risk:**
- **Schema:** `V1__create_user_profiles.sql` uses only `BIGINT / VARCHAR / DATE /
  TIMESTAMP` — all natively MySQL-compatible. To match the fleet convention, audit
  columns should become `DATETIME(6)` (as transaction/budget use) rather than MySQL
  `TIMESTAMP` (which has 2038-range and implicit default/on-update quirks). One-line type
  edits; the migration has never run in prod, so it can be edited directly (no
  applied-migration immutability concern in dev — confirm before any shared env).
- **JPA:** `UserProfile` maps cleanly to MySQL — `Long` PK (app-assigned, no
  `@GeneratedValue`), `String`/`LocalDate`/`LocalDateTime` fields. JPA auditing
  (`@CreatedDate`/`@LastModifiedDate`) is engine-agnostic. `ddl-auto: validate` will pass
  against the MySQL types above.
- **No Postgres-specific SQL or types to port** (none exist), so there is nothing that
  *fails* to translate — the usual hard part of a DB migration is absent here.

**Migration effort — small (estimated ~1–2 hours incl. rebuild & verify):**
1. **pom.xml:** swap `org.postgresql:postgresql` → `com.mysql:mysql-connector-j`; add
   `org.flywaydb:flyway-mysql` (mirror transaction/budget poms). Optionally switch the
   test DB to a MySQL Testcontainer to match siblings.
2. **application.yml:** datasource url → `jdbc:mysql://...:3306/user_db`, username `root`,
   `driver-class-name: com.mysql.cj.jdbc.Driver`; add explicit
   `dialect: org.hibernate.dialect.MySQLDialect` (fleet convention).
3. **V1 migration:** change `TIMESTAMP` → `DATETIME(6)`; add the
   `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci` clause for
   consistency. (Types otherwise unchanged.)
4. **docker-compose.yml:** point user-service at the `mysql` service + `depends_on`;
   drop the `postgres` service and `postgres_data` volume (no other consumer).
5. **docker/mysql/init/01-create-databases.sql:** add `CREATE DATABASE IF NOT EXISTS
   user_db ...`.
6. Rebuild image; verify boot + `GET /api/v1/users/me` end-to-end through the gateway.
- **Data migration:** none needed — no production data; user_db is empty (service has
  never started successfully).
- **Pros:** single engine for all four services; consistent conventions, tooling, and
  ops; smaller stack (one less container + volume); removes the class of bug that caused
  today's outage. **Cons:** slightly larger diff than Option A; forfeits the (currently
  unused) Postgres option.

---

## 3. Recommendation

**Migrate user-service to MySQL (Option B).**

Rationale: PostgreSQL provides **no realized benefit** here (no Postgres-specific feature
is used, not even in tests), while imposing the full operational cost of a second engine
on a 4-service platform — and it is the direct cause of the current outage. The
migration is **low-risk and small**: the schema is already portable, the JPA mapping is
engine-neutral, the PK is app-assigned (no sequence/identity translation), and there is
**no data to migrate**. Consolidating onto the shared MySQL 8 instance makes all four
services consistent in engine, dialect config, Flyway module, and test strategy.

Keep PostgreSQL (Option A) only if there is a *known, near-term* requirement for a
Postgres-specific capability (e.g. `JSONB` profiles, full-text profile search). No such
requirement is documented today; absent one, the second engine is unjustified.

If the team's deliberate intent **is** to showcase polyglot persistence, that is a valid
*non-functional* goal — but it should be (a) documented with that rationale, and (b)
completed correctly (add `flyway-database-postgresql`, use a Postgres Testcontainer).
Right now it is neither, which is why this reads as drift.

> Note on the immediate outage: whichever path is chosen, the one-line fix that *would*
> make the current Postgres setup boot is adding `flyway-database-postgresql`. **Per the
> task, that has not been applied** — this document is the strategy decision that should
> precede any fix.

---

### Appendix — evidence index
- `docker-compose.yml` — mysql (shared) + postgres (user only) + per-service env.
- `services/user-service/pom.xml` — has `postgresql`, **missing** `flyway-database-postgresql`.
- `services/{auth,transaction,budget}-service/pom.xml` — each has `flyway-mysql` ("required on Flyway 10+").
- `services/user-service/src/main/resources/db/migration/V1__create_user_profiles.sql` — portable types only.
- `services/user-service/src/main/java/.../entity/UserProfile.java` — app-assigned `Long` PK, engine-neutral.
- `services/transaction-service/.../V1__create_transactions.sql` — fleet convention: `DATETIME(6)`, `ENGINE=InnoDB ... utf8mb4`.
- README.md §Tech Stack / Services — documents the split but states no rationale for Postgres.
- git: user-service first commit 2026-06-01 (between auth 05-28 and transaction 06-02).
