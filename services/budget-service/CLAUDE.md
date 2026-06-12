# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`budget-service` is one service in the **FinSight** monorepo (`D:\finsight\services\`,
alongside `auth-service`, `user-service` and `transaction-service`). It owns **budget
definitions**: a user's spending limit for a category over a period. It is
self-contained: it does **not** call any other service at runtime, and it must not
touch their code.

Stack: Java 21 + Spring Boot 4.0.6 + Spring Data JPA + Flyway + MySQL.
Listens on port **8084** (auth=8081, user=8082, transaction=8083 by convention).

It is modelled directly on `transaction-service` (the reference implementation) and
shares its conventions verbatim: the response envelope, the JWT stack (same shared
secret), the exception handler, soft delete, JPA auditing, 1-based pagination, and
the Testcontainers test style.

## Scope — and what is deliberately NOT here

This service stores budget **definitions** and maintains `spent_amount`, an
**event-driven materialization** of matching EXPENSE spend consumed from Kafka
(`TransactionCreated` on `finsight.transactions.created`, Phase 2.2 — see
`docs/ADR-0004` at the repo root). There are still no cross-service runtime calls;
the only inbound data path besides HTTP is the Kafka listener.

`spent_amount` is **eventually consistent and can drift**: there are no
TransactionUpdated/TransactionDeleted events and no backfill, so dashboard-service's
live computation over transaction-service summaries remains the accurate view. This
tradeoff is accepted and documented in ADR-0004 — do not "fix" it casually.

Deliberately deferred: alerts/notifications, dashboards/analytics rollups, recurring
auto-generation, overall (all-category) budgets, cross-service category validation,
and FX conversion (the consumer matches on exact currency only).

## Commands

Use the Maven wrapper. On Windows use `mvnw.cmd`; on the Bash tool use `./mvnw`.

```bash
mvnw.cmd test                                 # run all tests (needs Docker for Testcontainers)
mvnw.cmd test -Dtest=CurrencyValidatorTest    # single test class
mvnw.cmd package                              # build jar
mvnw.cmd spring-boot:run                      # run locally (needs env below)
```

Running locally requires these env vars (DB defaults exist; `JWT_SECRET` does not):

```
JWT_SECRET=<same secret as auth-service>   # required, no default — app won't start without it
DB_URL=jdbc:mysql://localhost:3306/budget_db
DB_USERNAME=root
DB_PASSWORD=
```

Create the database before first run: `CREATE DATABASE budget_db;`. Integration tests
run against a real MySQL 8 Testcontainer (Docker required); unit tests do not.

## Architecture and conventions

Layering is strict and one-directional: `controller → service → repository`.

- **Controllers are thin.** They resolve `userId` from the authenticated principal,
  delegate to the service, and wrap results in the response envelope. No business
  logic. See `BudgetController`.
- **`userId` is sacred.** Read **only** from the JWT (`userId` claim) via
  `JwtUserPrincipal`, never from the request body. Request DTOs omit a `userId` field.
  Every service method takes `userId` first; every repository query / Specification is
  scoped by `userId`.

### Security / JWT
- Identical to transaction-service: `JwtAuthenticationFilter` validates the bearer
  token locally with the shared HMAC `JWT_SECRET` (`JwtService`). Never add a network
  call to auth-service per request. `SecurityConfig` is stateless; only
  `/actuator/health` and `/actuator/info` are public.

### Persistence
- `Budget` PK is a `UUID` generated in app code (`UUID.randomUUID()` in the service).
- **Soft delete only**: deletes set `is_deleted = true`. All reads go through
  `findByIdAndUserIdAndIsDeletedFalse` or Specifications adding `isDeleted = false`.
- Schema is owned by **Flyway** (`src/main/resources/db/migration/`), JPA is
  `ddl-auto=validate`. Any schema change is a new `V{n}__*.sql`; never edit an applied
  migration. The three indexes on `budgets` (`user_id`; `user_id, category_id`;
  `user_id, start_date, end_date`) serve the list/filter query — keep filtering scoped.
- `categoryId` is an **opaque reference** to a transaction-service category — NOT a
  foreign key and NOT validated cross-service (the same way transactions reference
  `wallet_id`).
- List filtering is built dynamically in `BudgetSpecifications` (JPA Criteria).
- `createdAt` / `updatedAt` are managed by JPA auditing (`AuditingConfig`).

### Kafka consumer (budget utilization)
- `TransactionEventConsumer` (gated by `finsight.kafka.enabled`; off in the test
  profile) consumes `TransactionCreated` and delegates to
  `BudgetService.applyExpense(...)`.
- **Matching**: same `userId` + `categoryId` + `currency`, `transactionDate` within
  `[startDate, endDate]`, not soft-deleted. `periodType` plays no role. One event may
  increment **several overlapping budgets** — that is correct, not a bug.
- **EXPENSE only**; unknown types, null/unparseable dates and missing eventIds are
  ignored (and deliberately NOT recorded in the inbox).
- **Idempotency**: `processed_events` inbox row written in the same DB transaction as
  the increment; redelivered eventIds are skipped. Never bypass it.
- **`spent_amount` is only ever written via `BudgetRepository.applyExpense`** (atomic
  SQL increment). Never set it through the entity — read-modify-write loses updates.
- The consumer-side `TransactionCreatedEvent` record is a deliberate copy of the
  producer's wire contract (`type` as String for leniency). Do not import or share
  code with transaction-service.

### Domain rules
- `limitAmount` must be `> 0` (Bean Validation + service guard + DB CHECK).
- `endDate >= startDate` (service guard + DB CHECK).
- Duplicate guard: at most one **active** budget per
  `(userId, categoryId, periodType, startDate)` → `409 BUDGET_ALREADY_EXISTS`.
  Enforced in the service over non-deleted rows (NOT a DB UNIQUE constraint, which
  would block re-creating a budget after soft-delete).
- `periodType` enum (`MONTHLY/WEEKLY/YEARLY/CUSTOM`) records intent; the explicit
  start/end dates carry the actual range.

### API contract
- **Pagination is 1-based in the API**, 0-based in Spring Data — the service
  subtracts 1. Preserve this translation.
- Success envelope: `{ "success": true, "data": ..., "meta": ... }` (`ApiResponse`).
  Error envelope: `{ "success": false, "error": { "code", "message" } }`.
- All errors flow through `GlobalExceptionHandler` (`@RestControllerAdvice`) with
  stable string codes (`BUDGET_NOT_FOUND`, `BUDGET_ALREADY_EXISTS`, `VALIDATION_ERROR`).
- `currency` uses the custom `@ValidCurrency` (ISO 4217). Update is partial: only
  non-null fields on `UpdateBudgetRequest` are applied.
