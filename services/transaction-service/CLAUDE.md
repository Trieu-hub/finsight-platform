# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`transaction-service` is one service in the **FinSight** monorepo (`D:\finsight\services\`,
alongside `auth-service` and `user-service`). It owns transactions and their
categories. It is self-contained: it does **not** call `auth-service` or
`user-service` at runtime, and it must not touch their code.

Stack: Java 21 + Spring Boot 4.0.6 + Spring Data JPA + Flyway + MySQL.
Listens on port **8083** (auth=8081, user=8082 by convention).

> This service runs on **MySQL 8** (`transaction_db` on the shared instance) and targets
> **Java 21** (`pom.xml`), consistent with the platform `README.md`. The `metadata` column
> uses MySQL's native `json` type.

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
DB_URL=jdbc:mysql://localhost:3306/transaction_db
DB_USERNAME=root
DB_PASSWORD=
```

Create the database before first run: `CREATE DATABASE transaction_db;`. Integration tests
run against a real MySQL 8 Testcontainer (Docker required); unit tests do not.

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
- `metadata` is a `Map<String,Object>` stored as a MySQL `json` column
  (`@JdbcTypeCode(SqlTypes.JSON)`, `columnDefinition = "json"`).
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

### Transaction types & wallets
- `TransactionType` is `INCOME` / `EXPENSE` / `TRANSFER`. TRANSFER is a wallet-to-wallet
  move (neither income nor expense); it requires distinct `walletId` (source) and
  `toWalletId` (destination), and uses the seeded system `Transfer` category (type
  `TRANSFER`, id 11 from `V5`). Downstream consumers (budget/risk/analytics) already
  ignore non-INCOME/EXPENSE types by design — do not "fix" that.
- **Wallets** (`wallets` table, `V6`) are first-class accounts owned by this service:
  `WalletController` / `WalletService` expose CRUD at `/api/v1/wallets`, user-scoped, one
  currency each. `balance` is a **stored** field maintained by transaction writes, never
  set through the wallet API.
- **Balance maintenance is atomic**: `WalletService.applyTransactionEffect(...)` runs inside
  the transaction service's `@Transactional` via `WalletRepository.adjustBalance` (a single
  SQL increment). create applies (+1), delete reverses (−1), update reverses the old effect
  then applies the new one. A transaction's `currency` must equal the wallet's currency
  (no FX); a wallet referenced by a transaction must be owned by the same user. A wallet
  with a non-zero balance cannot be deleted (`WALLET_NOT_EMPTY`). `wallet_id`/`to_wallet_id`
  remain **opaque** at the DB level (no FK) — integrity is enforced in the service.
