# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw.cmd clean install

# Run
./mvnw.cmd spring-boot:run

# Run tests
./mvnw.cmd test

# Run a single test class
./mvnw.cmd test -Dtest=AuthServiceApplicationTests

# Package (skip tests)
./mvnw.cmd package -DskipTests
```

## Architecture

Spring Boot 4.0.6 / Java 21 microservice handling authentication for the `finsight` platform. Runs on port `8081`.

**Data layer:**
- MySQL (`auth_db`) — schema is owned entirely by Flyway (`src/main/resources/db/migration/`). Hibernate is set to `validate` only; never use `ddl-auto: create/update`.
- Redis — backs refresh-token storage, brute-force lockout, and the access-token revocation denylist. Its health check is enabled and readiness depends on it: a node that cannot reach Redis cannot issue tokens.
- All new migrations must follow Flyway naming: `V{n}__{description}.sql`.

**Domain model (3 tables):**
- `roles` — stores `RoleName` enum values (`USER`, `PREMIUM`, `ADMIN`).
- `users` — email + bcrypt password, `enabled` flag, single `Role` FK (EAGER-loaded).
- `refresh_tokens` — opaque token string + `expiryDate`, FK to `users` (LAZY-loaded).

**Package layout under `com.pm.authservice`:**
- `entity/` — JPA entities, all use Lombok `@Builder` + `@Getter`/`@Setter`.
- `enums/` — `RoleName`.
- `repository/` — `UserRepository` / `RoleRepository`, both `JpaRepository`.
- `security/jwt/` — `JwtKeyRegistry` (holds the signing key, derives each `kid` as an RFC 7638 thumbprint, renders the JWK Set), `JwtService`, `JwtProperties`.
- `controller/` — `AuthController` (`/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`), `AdminController`, `JwksController` (`/.well-known/jwks.json`).

**This service alone holds `JWT_PRIVATE_KEY`** — it is the only component that can mint a token. Every other service verifies with the public key. See [docs/security/jwt-key-rotation.md](../../docs/security/jwt-key-rotation.md) for how rotation works.

## Key constraints

- `ddl-auto: validate` means a missing or mismatched Flyway migration will crash startup.
- The private key never leaves this service. Never add an endpoint, log line, or response that could emit it — `/.well-known/jwks.json` publishes public keys only, and `JwtKeyRegistry` renders it through `Jwks.json(PublicJwk)` so leaking private material is a compile error.
- `RS256` is pinned by [ADR-0002](../api-gateway/docs/ADR-0002-identity-auth-contract-freeze.md); the claim set (`sub`/`userId`/`email`/`role`/`iss`/`aud`) is frozen there too. Adding or renaming a claim breaks all seven validators at once.
