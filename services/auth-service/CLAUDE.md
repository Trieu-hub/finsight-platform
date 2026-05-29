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
- Redis — wired in but health-check is disabled; intended for token/session caching (not yet implemented).
- All new migrations must follow Flyway naming: `V{n}__{description}.sql`.

**Domain model (3 tables):**
- `roles` — stores `RoleName` enum values (`USER`, `PREMIUM`, `ADMIN`).
- `users` — email + bcrypt password, `enabled` flag, single `Role` FK (EAGER-loaded).
- `refresh_tokens` — opaque token string + `expiryDate`, FK to `users` (LAZY-loaded).

**Package layout under `com.pm.authservice`:**
- `entity/` — JPA entities, all use Lombok `@Builder` + `@Getter`/`@Setter`.
- `enums/` — `RoleName`.
- `repository/` — stubs not yet extended from `JpaRepository`; needs completion before service layer is added.

**Planned but not yet implemented:** Spring Security config, JWT issuance/validation, REST controllers (`/auth/login`, `/auth/refresh`, `/auth/register`), Redis token store.

## Key constraints

- Repositories in `repository/` are currently empty classes — extend `JpaRepository<Entity, Long>` and add any custom query methods before writing service logic.
- `ddl-auto: validate` means a missing or mismatched Flyway migration will crash startup.
- Redis health check is disabled in `application.yml`; keep it disabled until Redis is actually used in production.
