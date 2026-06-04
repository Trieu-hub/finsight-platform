# FinSight

Financial Intelligence & Risk Monitoring Platform.

A Spring Boot microservice monorepo. Each service is self-contained and owns its
own database; services do **not** call each other at runtime.

## Tech Stack

- Java 21, Spring Boot 4.0.6
- MySQL 8 (auth, transaction, budget) and PostgreSQL 16 (user)
- Redis (auth — wired, not yet used)
- Flyway (schema ownership, `ddl-auto: validate`)
- Docker / Docker Compose
- Kubernetes (planned)

## Services

| Service               | Port | Database                | Engine      | Key responsibility                                   |
|-----------------------|------|-------------------------|-------------|------------------------------------------------------|
| `auth-service`        | 8081 | `auth_db`               | MySQL       | Registration, login, JWT issuance (+ Redis)          |
| `user-service`        | 8082 | `user_db`               | PostgreSQL  | User profile data (consumes JWTs)                    |
| `transaction-service` | 8083 | `transaction_db`        | MySQL       | Transactions + categories, summaries                 |
| `budget-service`      | 8084 | `budget_db`             | MySQL       | Budget definitions (spending limit per category)     |

Shared infrastructure: a single **MySQL 8** instance hosts `auth_db`,
`transaction_db` and `budget_db`; a separate **PostgreSQL 16** instance hosts
`user_db`; **Redis** is available for auth-service.

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
     PostgreSQL              MySQL                  MySQL
      user_db          transaction_db             budget_db
```

Key design rules (consistent across all services):

- **No runtime cross-service calls.** The only coupling is the shared HMAC
  `JWT_SECRET`: auth-service issues tokens; the others validate them locally.
  Each service therefore depends only on its own datastore.
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
Dockerfiles) and starts MySQL, PostgreSQL and Redis. All services share one
`JWT_SECRET` so tokens issued by auth-service validate everywhere.

```bash
docker compose up --build        # build images + start the full stack
docker compose up -d mysql postgres redis   # start just the datastores
docker compose down              # stop (add -v to also drop the DB volumes)
```

Service health: `GET http://localhost:<port>/actuator/health` (public on every
service). Databases are created automatically — MySQL via
`docker/mysql/init/01-create-databases.sql`, PostgreSQL via `POSTGRES_DB`.

## Running a single service locally

Each service has its own `CLAUDE.md` / `HELP.md` with details. In general:

```bash
cd services/<service>
./mvnw spring-boot:run     # mvnw.cmd on Windows
./mvnw test                # transaction/budget integration tests need Docker (Testcontainers)
```

Local runs need a reachable database and (for user/transaction/budget) a
`JWT_SECRET` env var matching auth-service's secret.
