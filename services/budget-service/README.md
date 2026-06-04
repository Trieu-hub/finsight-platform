# budget-service

Budget definitions for the **FinSight** platform. Owns a user's spending limits per
category and period. Java 21 · Spring Boot 4.0.6 · Spring Data JPA · Flyway · MySQL.
Port **8084**.

## Scope

Stores budget **definitions only** (limit + period + category). It does **not**
compute "spent vs. limit" — spend lives in `transaction-service`, and services never
call each other at runtime. Progress composition is a future dashboard/BFF concern.

## Run

```bash
# requires a running MySQL and: CREATE DATABASE budget_db;
JWT_SECRET=<same secret as auth-service> \
DB_URL=jdbc:mysql://localhost:3306/budget_db DB_USERNAME=root DB_PASSWORD= \
mvnw.cmd spring-boot:run
```

`JWT_SECRET` is required (no default). Tokens are validated locally with the secret
shared with `auth-service`.

## Test

```bash
mvnw.cmd test     # unit + integration (integration needs Docker for Testcontainers MySQL 8)
```

## API

All endpoints require a `Bearer <jwt>` token; `userId` is taken from the token only.

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/api/v1/budgets`      | Create a budget (201) |
| GET    | `/api/v1/budgets`      | List with filters `categoryId`, `periodType`, `activeOn`, `page`, `limit` (1-based) |
| GET    | `/api/v1/budgets/{id}` | Get one (404 if not owned) |
| PUT    | `/api/v1/budgets/{id}` | Partial update (non-null fields only) |
| DELETE | `/api/v1/budgets/{id}` | Soft delete (204) |

Envelopes: success `{ "success": true, "data": ..., "meta": ... }`,
error `{ "success": false, "error": { "code", "message" } }`.

### Create body

```json
{
  "name": "Groceries",
  "categoryId": 4,
  "periodType": "MONTHLY",
  "startDate": "2026-06-01",
  "endDate": "2026-06-30",
  "limitAmount": 500.00,
  "currency": "USD"
}
```

`categoryId` is an opaque reference to a transaction-service category (not validated
cross-service). `limitAmount > 0` and `endDate >= startDate` are enforced. At most one
active budget may exist per `(userId, categoryId, periodType, startDate)` —
otherwise `409 BUDGET_ALREADY_EXISTS`.

See `CLAUDE.md` for full conventions and the rationale behind what is deferred.
