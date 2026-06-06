# FinSight

Financial Intelligence & Risk Monitoring Platform.

A Spring Boot microservice monorepo. Each service is self-contained and owns its
own database; services do **not** call each other at runtime.

## Tech Stack

- Java 21, Spring Boot 4.0.6
- MySQL 8 (auth, user, transaction, budget — one shared instance)
- Redis (auth — wired, not yet used)
- Flyway (schema ownership, `ddl-auto: validate`)
- Docker / Docker Compose
- Kubernetes (planned)

## Services

| Service               | Port | Database                | Engine      | Key responsibility                                   |
|-----------------------|------|-------------------------|-------------|------------------------------------------------------|
| `auth-service`        | 8081 | `auth_db`               | MySQL       | Registration, login, JWT issuance (+ Redis)          |
| `user-service`        | 8082 | `user_db`               | MySQL       | User profile data (consumes JWTs)                    |
| `transaction-service` | 8083 | `transaction_db`        | MySQL       | Transactions + categories, summaries                 |
| `budget-service`      | 8084 | `budget_db`             | MySQL       | Budget definitions (spending limit per category)     |
| `dashboard-service`   | 8085 | _(none)_                | —           | Read-only aggregation / BFF (no DB; calls the others)|
| `api-gateway`         | 8080 | _(none)_                | —           | Edge routing + JWT validation (Phase 2)              |

Shared infrastructure: a single **MySQL 8** instance hosts `auth_db`, `user_db`,
`transaction_db` and `budget_db` (each service owns its own logical database);
**Redis** is available for auth-service.

## Architecture

```
                       ┌───────────────┐
                       │ auth-service  │  issues JWT (HMAC, shared secret)
                       │   :8081       │
                       └───────┬───────┘
            JWT (Bearer) validated locally by every service
            ┌──────────────────┼──────────────────┐
            ▼                   ▼                   ▼
   ┌────────────────┐ ┌────────────────────┐ ┌────────────────┐
   │ user-service   │ │ transaction-service│ │ budget-service │
   │   :8082        │ │   :8083            │ │   :8084        │
   └───────┬────────┘ └─────────┬──────────┘ └───────┬────────┘
           ▼                    ▼                     ▼
       MySQL                  MySQL                  MySQL
      user_db          transaction_db             budget_db
```

Key design rules (consistent across all services):

- **No runtime cross-service calls** between the core business services. The only
  coupling is the shared HMAC `JWT_SECRET`: auth-service issues tokens; the others
  validate them locally. Each owns only its own datastore. **Exception:**
  `dashboard-service` is a read-only BFF that *does* call the others over HTTP,
  relaying the caller's JWT (see `docs/ADR-0003`); it owns no data of its own.
- **`userId` is sacred** — read only from the JWT `userId` claim (`BIGINT`/`Long`),
  never from the request body or URL.
- **Identifier contract.** Cross-service identifiers are uniformly `Long`/`BIGINT`:
  `userId` everywhere, and `categoryId` (owned by transaction-service's `categories`
  table; referenced as an opaque value by budget-service). Transaction and budget
  rows use app-generated `UUID` primary keys; auth/user rows use `BIGINT` PKs — an
  intentional per-entity choice, not an inconsistency.
- **Schema is owned by Flyway**; Hibernate runs `validate` only.
- **Soft delete + 1-based API pagination + a shared response/error envelope** are
  used by transaction-service and budget-service.

## Running the platform (Docker Compose)

The root `docker-compose.yml` builds all four services from source (multi-stage
Dockerfiles) and starts MySQL and Redis. All services share one `JWT_SECRET` so
tokens issued by auth-service validate everywhere.

```bash
docker compose up --build        # build images + start the full stack
docker compose up -d mysql redis # start just the datastores
docker compose down              # stop (add -v to also drop the DB volumes)
```

Service health: `GET http://localhost:<port>/actuator/health` (public on every
service). All four logical databases (`auth_db`, `user_db`, `transaction_db`,
`budget_db`) are created automatically on first MySQL start via
`docker/mysql/init/01-create-databases.sql`.

## Running a single service locally

Each service has its own `CLAUDE.md` / `HELP.md` with details. In general:

```bash
cd services/<service>
./mvnw spring-boot:run     # mvnw.cmd on Windows
./mvnw test                # transaction/budget integration tests need Docker (Testcontainers)
```

Local runs need a reachable database and (for user/transaction/budget) a
`JWT_SECRET` env var matching auth-service's secret.
