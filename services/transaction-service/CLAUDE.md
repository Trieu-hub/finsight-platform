# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`transaction-service` is one service in the **FinSight** monorepo (`D:\finsight\services\`,
alongside `auth-service` and `user-service`). It owns transactions and their
categories. It is self-contained: it does **not** call `auth-service` or
`user-service` at runtime, and it must not touch their code.

Stack: Java 21 + Spring Boot 4.0.6 + Spring Data JPA + Flyway + PostgreSQL.
Listens on port **8083** (auth=8081, user=8082 by convention).

> The root `README.md` lists MySQL/Java 23 for the platform, but this service
> uses **PostgreSQL** (JSONB columns) and targets **Java 21** (`pom.xml`). Follow
> the service's own config, not the platform README.

## Commands

Use the Maven wrapper. On Windows use `mvnw.cmd`; on the Bash tool use `./mvnw`.

```bash
mvnw.cmd test                                   # run all tests
mvnw.cmd test -Dtest=CurrencyValidatorTest      # single test class
mvnw.cmd test -Dtest=CurrencyValidatorTest#rejectsUnknownCodes   # single method
mvnw.cmd package                                # build jar
mvnw.cmd spring-boot:run                        # run locally (needs env below)
```

Running locally requires these env vars (DB defaults exist; `JWT_SECRET` does not):

```
JWT_SECRET=<same secret as auth-service>   # required, no default — app won't start without it
DB_URL=jdbc:postgresql://localhost:5432/transaction_db
DB_USERNAME=postgres
DB_PASSWORD=postgres
```

Create the database before first run: `CREATE DATABASE transaction_db;`. Tests
run against in-memory H2, so no Postgres is needed for `mvnw test`.

## Architecture and conventions

Layering is strict and one-directional: `controller → service → repository`.

- **Controllers are thin.** They resolve `userId` from the authenticated
  principal, delegate to the service, and wrap results in the response envelope.
  No business logic. See `TransactionController`.
- **`userId` is sacred.** It is read **only** from the JWT (`userId` claim,
  numeric/BIGINT) via `JwtUserPrincipal`, never from the request body. Request
  DTOs deliberately omit a `userId` field. Every service method takes `userId`
  as its first argument, and every repository query / Specification is scoped by
  `userId` — this both enforces tenant isolation and keeps the `user_id` indexes
  in play. Preserve this when adding endpoints.

### Security / JWT
- `JwtAuthenticationFilter` (a `OncePerRequestFilter`, acts as the auth guard)
  validates the bearer token on every request and populates the SecurityContext.
- Tokens are validated **locally** with the shared HMAC `JWT_SECRET`
  (`JwtService`). Never add a network call to `auth-service` per request.
- `SecurityConfig` is stateless (no sessions, CSRF disabled). Only
  `/actuator/health` and `/actuator/info` are public; everything else requires a
  valid token.

### Persistence
- `Transaction` PK is a `UUID` generated in app code (`UUID.randomUUID()` in the
  service), not by the DB.
- **Soft delete only**: deletes set `is_deleted = true`. All reads go through
  `findByIdAndUserIdAndIsDeletedFalse` or Specifications that add
  `isDeleted = false`. Never hard-delete; never forget the `isDeleted` filter on
  a new query.
- Schema is owned by **Flyway** (`src/main/resources/db/migration/`). JPA is
  `ddl-auto=validate` — it will **not** create or alter tables. Any schema change
  is a new `V{n}__*.sql` migration; never edit an applied migration. The four
  indexes on `transactions` (`user_id`; `user_id, transaction_date`;
  `user_id, type`; `category_id`) exist to serve the list/filter query — keep
  filtering scoped so they're usable.
- List filtering is built dynamically in `TransactionSpecifications`
  (JPA Criteria), not with derived query methods.
- `metadata` is a `Map<String,Object>` stored as Postgres `jsonb`
  (`@JdbcTypeCode(SqlTypes.JSON)`).
- `createdAt` / `updatedAt` are managed by JPA auditing (`@EnableJpaAuditing` in
  `AuditingConfig`), not set manually.

### API contract
- **Pagination is 1-based in the API**, 0-based in Spring Data — the service
  subtracts 1 (`filter.getPage() - 1`). Preserve this translation.
- Success envelope: `{ "success": true, "data": ..., "meta": ... }`
  (`ApiResponse`, `meta` omitted when null). Error envelope:
  `{ "success": false, "error": { "code", "message" } }` (`ErrorResponse`).
- All errors flow through `GlobalExceptionHandler` (`@RestControllerAdvice`),
  which maps exceptions to stable string `code`s (e.g. `TRANSACTION_NOT_FOUND`,
  `CATEGORY_NOT_FOUND`, `VALIDATION_ERROR`). Throw a domain exception; don't
  build error responses in services/controllers.
- Bean Validation on request DTOs (`@Valid`). `currency` uses the custom
  `@ValidCurrency` (ISO 4217). Update is partial: only non-null fields on
  `UpdateTransactionRequest` are applied.
- `categoryId` is validated against the `categories` table (seeded by
  `V2__seed_categories.sql`) before a transaction is written.
