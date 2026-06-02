# transaction-service

Core financial-domain service for **FinSight**. Owns transactions and their
categories. Built to match the existing monorepo conventions (Java 21 +
Spring Boot 4 + Spring Data JPA + Flyway + PostgreSQL), consistent with
`auth-service` and `user-service`.

> Stack note: the original spec assumed Node/NestJS/Prisma, but the monorepo is
> Java/Spring Boot. This service follows the repo to stay consistent and to
> interoperate with the JWT issued by `auth-service` (numeric `userId`).

## Boundaries

- Isolated under `/services/transaction-service`. Does not touch `auth-service`
  or `user-service`.
- JWT access tokens are validated **locally** with the shared `JWT_SECRET`.
  The service never calls `auth-service` per request and never calls
  `user-service`.
- `userId` is taken **only** from the JWT (`userId` claim, numeric/BIGINT),
  never from the request body.

## Architecture

```
controller  -> thin HTTP layer, resolves userId from JWT, wraps responses
service     -> business logic (TransactionService / impl)
repository  -> DB access only (Spring Data JPA + Specifications)
dto         -> validation + transformation
security    -> JWT auth guard (filter) + principal
entity      -> JPA entities (Transaction, Category)
```

## Run

```bash
# from services/transaction-service
export JWT_SECRET=<same secret as auth-service>
export DB_URL=jdbc:postgresql://localhost:5432/transaction_db
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
./mvnw spring-boot:run
```

Service listens on port **8083** (auth=8081, user=8082 by convention).

## API

All routes require `Authorization: Bearer <access-token>` and are prefixed
`/api/v1/transactions`.

| Method | Path                       | Description                          |
|--------|----------------------------|--------------------------------------|
| POST   | `/api/v1/transactions`     | Create a transaction                 |
| GET    | `/api/v1/transactions`     | List (pagination + filters + sort)   |
| GET    | `/api/v1/transactions/{id}`| Get one by id                        |
| PUT    | `/api/v1/transactions/{id}`| Update (partial)                     |
| DELETE | `/api/v1/transactions/{id}`| Soft delete                          |

### GET query parameters

`page` (default 1), `limit` (default 10, max 100), `fromDate`, `toDate`
(`YYYY-MM-DD`), `type` (`INCOME`|`EXPENSE`), `categoryId`. Sorted by
`transactionDate DESC`.

### Example — create

```http
POST /api/v1/transactions
Authorization: Bearer <token>
Content-Type: application/json

{
  "type": "EXPENSE",
  "amount": 42.50,
  "currency": "USD",
  "categoryId": 4,
  "description": "Lunch",
  "transactionDate": "2026-06-01",
  "walletId": 1,
  "metadata": { "merchant": "Cafe" }
}
```

```json
{
  "success": true,
  "data": {
    "id": "0f8e...e1",
    "userId": 1001,
    "type": "EXPENSE",
    "amount": 42.5000,
    "currency": "USD",
    "categoryId": 4,
    "description": "Lunch",
    "transactionDate": "2026-06-01",
    "walletId": 1,
    "metadata": { "merchant": "Cafe" },
    "createdAt": "2026-06-02T10:15:00",
    "updatedAt": "2026-06-02T10:15:00"
  }
}
```

### Example — list

```json
{
  "success": true,
  "data": [ { "id": "...", "type": "EXPENSE", "amount": 42.5000 } ],
  "meta": { "page": 1, "limit": 10, "total": 100 }
}
```

### Error envelope

```json
{
  "success": false,
  "error": { "code": "TRANSACTION_NOT_FOUND", "message": "Transaction not found" }
}
```

## Migrations

Flyway runs automatically on startup (`spring.flyway.enabled=true`,
`ddl-auto=validate`).

- `V1__create_transactions.sql` — `categories` + `transactions` tables, the four
  mandatory indexes (`user_id`; `user_id, transaction_date`; `user_id, type`;
  `category_id`), and `amount > 0` / type check constraints.
- `V2__seed_categories.sql` — default categories used to validate `categoryId`.

Create the database before first run: `CREATE DATABASE transaction_db;`
