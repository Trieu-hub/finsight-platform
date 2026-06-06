# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw clean package -DskipTests

# Run
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=UserServiceApplicationTests

# Compile only (fast check)
./mvnw compile
```

Environment variables required before running:
```
DB_URL=jdbc:mysql://localhost:3306/user_db
DB_USERNAME=root
DB_PASSWORD=<secret>
JWT_SECRET=<same secret as auth-service>
```

The service starts on port **8082**.

## Architecture

This is a Spring Boot 4 / Java 21 microservice within the `finsight-platform`. It is one of at least two services — `auth-service` issues JWTs, `user-service` (this repo) consumes them to manage user profile data.

**Key design decisions:**

- **No `UserDetailsService`.** Authentication is entirely JWT-based. `JwtAuthenticationFilter` validates the token, extracts `userId` / `email` / `role` claims, and sets a `JwtUserPrincipal` into the `SecurityContextHolder`. No database lookup happens in the security layer.
- **`userId` comes only from the JWT**, never from request body or URL path. The controller always calls `(JwtUserPrincipal) authentication.getPrincipal()` to get the caller's identity. This means controllers must receive `Authentication authentication` as a parameter.
- **Separate database from auth-service.** `user_db` stores only profile data (`user_profiles` table). Identity fields (`email`, `username`, `password`, `role`, `enabled`) live exclusively in the auth-service DB. There is no FK constraint between the two databases. Both run on the shared **MySQL 8** instance (separate logical databases), consistent with the rest of the platform — Flyway requires the `flyway-mysql` module on Flyway 10+.
- **Flyway manages schema.** `ddl-auto: validate` — Hibernate validates against the schema but never modifies it. New schema changes require a new `V{n}__description.sql` migration file under `src/main/resources/db/migration/`.
- **JPA Auditing** populates `createdAt` / `updatedAt` automatically via `@EnableJpaAuditing` (in `AuditingConfig`) and `@CreatedDate` / `@LastModifiedDate` on the entity.

**Request flow:**
```
HTTP Request
  → JwtAuthenticationFilter     (validates JWT, sets SecurityContext)
  → SecurityConfig              (all /api/v1/users/** require auth; actuator endpoints are public)
  → UserProfileController       (extracts userId from principal)
  → UserProfileService          (interface)
  → UserProfileServiceImpl      (business logic + repo calls)
  → UserProfileRepository       (Spring Data JPA)
  → MySQL user_db
```

**Package layout** (`com.pm.userservice`):
- **`UserDetailsServiceAutoConfiguration` is excluded** to suppress Spring Boot's default in-memory user creation. In Spring Boot 4.x this class lives at `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration` (moved from `org.springframework.boot.autoconfigure.security.servlet` in Boot 3).

- `controller/` — REST endpoints under `/api/v1/users`
- `service/` + `service/impl/` — service interface and implementation
- `repository/` — Spring Data JPA repository
- `entity/` — JPA entity (`UserProfile`)
- `dto/` — request/response objects (`CreateProfileRequest`, `UpdateProfileRequest`, `UserProfileResponse`)
- `security/` — `SecurityConfig`, `JwtUserPrincipal`; `security/jwt/` — `JwtAuthenticationFilter`, `JwtService`, `JwtProperties`
- `exception/` — `GlobalExceptionHandler` (`@RestControllerAdvice`), typed exceptions, `ErrorResponse`

## JWT Claims Contract

`JwtService` reads these custom claims from tokens issued by `auth-service`:

| Claim    | Type   | Extracted as        |
|----------|--------|---------------------|
| `userId` | Number | `Long` via `extractUserId()` |
| `email`  | String | `extractEmail()`    |
| `role`   | String | `extractRole()`     |

Both services must share the same `JWT_SECRET`. If the claim names change in `auth-service`, update `JwtService` accordingly.

## Adding New Endpoints

1. Keep `userId` sourced from `authentication.getPrincipal()` — never trust it from the client.
2. All endpoints under `/api/v1/users/**` are automatically protected. Add any new public paths explicitly to `SecurityConfig.securityFilterChain()`.
3. New domain exceptions should extend `RuntimeException` and be registered in `GlobalExceptionHandler`.
4. New schema changes go in a new Flyway migration file (`V2__...sql`, `V3__...sql`, etc.).
